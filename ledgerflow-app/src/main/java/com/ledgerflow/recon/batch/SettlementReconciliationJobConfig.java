package com.ledgerflow.recon.batch;

import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.service.ExpectedPayments;
import com.ledgerflow.recon.service.ReconciliationService;
import com.ledgerflow.recon.service.SettlementLine;
import com.ledgerflow.recon.service.StagedSettlementLine;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.file.FlatFileItemReader;
import org.springframework.batch.item.file.FlatFileParseException;
import org.springframework.batch.item.file.builder.FlatFileItemReaderBuilder;
import org.springframework.batch.item.file.transform.DelimitedLineTokenizer;
import org.springframework.batch.item.file.transform.FieldSet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.UUID;

/**
 * Nightly settlement reconciliation as a Spring Batch job.
 *
 * <pre>
 *   registerSettlementFile  (tasklet)  create/find the settlement_files row
 *   stageSettlementLines    (chunk 500) CSV -> settlement_lines; bad lines skipped and counted
 *   matchSettlementLines    (tasklet)  set-based SQL comparison -> recon_items
 * </pre>
 *
 * <p>Job parameters: {@code input.file} (identifying: the same file cannot be reconciled twice),
 * and optional non-identifying {@code window.start} / {@code window.end} (ISO-8601 instants) that
 * define which ledger payments the bank was expected to settle.
 *
 * <p>Restartable: the reader saves its line position after each committed chunk, staging uses
 * ON CONFLICT DO NOTHING, and matching is idempotent per file.
 *
 * <p>CSV format (header row required): {@code line_id,payment_reference,amount_minor,currency,status}
 */
@Configuration
public class SettlementReconciliationJobConfig {

    public static final String JOB_NAME = "settlementReconciliationJob";
    public static final String PARAM_INPUT_FILE = "input.file";
    public static final String PARAM_WINDOW_START = "window.start";
    public static final String PARAM_WINDOW_END = "window.end";
    static final String CTX_FILE_ID = "settlementFileId";
    static final int CHUNK_SIZE = 500;
    static final int SKIP_LIMIT = 100;

    @Bean
    public Job settlementReconciliationJob(JobRepository jobRepository,
                                           Step registerSettlementFileStep,
                                           Step stageSettlementLinesStep,
                                           Step matchSettlementLinesStep) {
        return new JobBuilder(JOB_NAME, jobRepository)
                .start(registerSettlementFileStep)
                .next(stageSettlementLinesStep)
                .next(matchSettlementLinesStep)
                .build();
    }

    @Bean
    public Step registerSettlementFileStep(JobRepository jobRepository, PlatformTransactionManager tx,
                                           ReconciliationService recon) {
        return new StepBuilder("registerSettlementFile", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    String path = (String) chunkContext.getStepContext().getJobParameters().get(PARAM_INPUT_FILE);
                    String filename = new FileSystemResource(path).getFilename();
                    SettlementFile file = recon.registerFile(filename);
                    chunkContext.getStepContext().getStepExecution().getJobExecution()
                            .getExecutionContext().putString(CTX_FILE_ID, file.getId().toString());
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }

    @Bean
    public Step stageSettlementLinesStep(JobRepository jobRepository, PlatformTransactionManager tx,
                                         FlatFileItemReader<StagedSettlementLine> settlementLineReader,
                                         ItemWriter<StagedSettlementLine> settlementLineWriter) {
        return new StepBuilder("stageSettlementLines", jobRepository)
                .<StagedSettlementLine, StagedSettlementLine>chunk(CHUNK_SIZE, tx)
                .reader(settlementLineReader)
                .writer(settlementLineWriter)
                .faultTolerant()
                .skip(FlatFileParseException.class)
                .skipLimit(SKIP_LIMIT)
                .build();
    }

    @Bean
    public Step matchSettlementLinesStep(JobRepository jobRepository, PlatformTransactionManager tx,
                                         ReconciliationService recon) {
        return new StepBuilder("matchSettlementLines", jobRepository)
                .tasklet((contribution, chunkContext) -> {
                    var stepExecution = chunkContext.getStepContext().getStepExecution();
                    UUID fileId = UUID.fromString(stepExecution.getJobExecution().getExecutionContext().getString(CTX_FILE_ID));
                    var params = stepExecution.getJobParameters();
                    String start = params.getString(PARAM_WINDOW_START);
                    String end = params.getString(PARAM_WINDOW_END);
                    ExpectedPayments expected = (start != null && end != null)
                            ? ExpectedPayments.window(Instant.parse(start), Instant.parse(end))
                            : ExpectedPayments.none();
                    int mismatches = recon.matchStagedFile(fileId, expected).size();
                    stepExecution.getExecutionContext().putInt("mismatches", mismatches);
                    contribution.incrementWriteCount(mismatches);
                    return RepeatStatus.FINISHED;
                }, tx)
                .build();
    }

    @Bean
    @StepScope
    public FlatFileItemReader<StagedSettlementLine> settlementLineReader(
            @Value("#{jobParameters['" + PARAM_INPUT_FILE + "']}") String path) {
        DelimitedLineTokenizer tokenizer = new DelimitedLineTokenizer();
        tokenizer.setNames("line_id", "payment_reference", "amount_minor", "currency", "status");
        tokenizer.setStrict(true);

        return new FlatFileItemReaderBuilder<StagedSettlementLine>()
                .name("settlementLineReader")
                .resource(new FileSystemResource(path))
                .linesToSkip(1)
                .lineMapper((line, lineNumber) -> toStagedLine(tokenizer.tokenize(line), lineNumber))
                .build();
    }

    @Bean
    @StepScope
    public ItemWriter<StagedSettlementLine> settlementLineWriter(
            @Value("#{jobExecutionContext['" + CTX_FILE_ID + "']}") String fileId,
            ReconciliationService recon) {
        UUID id = UUID.fromString(fileId);
        return chunk -> recon.stageLines(id, chunk.getItems().stream().map(StagedSettlementLine.class::cast).toList());
    }

    /** Validates one CSV row. Any exception here is wrapped by the reader as a skippable FlatFileParseException. */
    static StagedSettlementLine toStagedLine(FieldSet fs, int lineNumber) {
        String ref = fs.readString("payment_reference");
        if (ref == null || ref.isBlank() || ref.length() > 128) {
            throw new IllegalArgumentException("invalid payment_reference");
        }
        // Strict minor-units integer. FieldSet.readLong would parse "12.50" as 12 (silent truncation).
        long amount = Long.parseLong(fs.readRawString("amount_minor").strip());
        String currency = fs.readString("currency");
        if (currency == null || !currency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("invalid ISO-4217 currency");
        }
        return new StagedSettlementLine(lineNumber,
                new SettlementLine(fs.readString("line_id"), ref.strip(), amount, currency, fs.readString("status")));
    }
}
