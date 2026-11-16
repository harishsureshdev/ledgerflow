package com.ledgerflow.recon.batch;

import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.batch.core.repository.JobInstanceAlreadyCompleteException;
import org.springframework.batch.core.repository.JobRestartException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

/** Launches the reconciliation job and turns its execution into a small summary. */
@Component
public class SettlementReconciliationRunner {

    public record RunSummary(
            Long jobExecutionId,
            String status,
            String settlementFileId,
            long linesStaged,
            long linesSkipped,
            int mismatches,
            String exitDescription
    ) {}

    /** The same file was already reconciled successfully; the job refuses to run it twice. */
    public static class AlreadyReconciledException extends RuntimeException {
        public AlreadyReconciledException(String filename) {
            super("Settlement file already reconciled: " + filename);
        }
    }

    private final JobLauncher jobLauncher;
    private final Job settlementReconciliationJob;

    public SettlementReconciliationRunner(JobLauncher jobLauncher, Job settlementReconciliationJob) {
        this.jobLauncher = jobLauncher;
        this.settlementReconciliationJob = settlementReconciliationJob;
    }

    /**
     * Runs the job synchronously.
     *
     * @param windowStart/windowEnd payments created in this window and CAPTURED/SETTLED are expected in
     *                              the file (MISSING_IN_BANK detection); pass nulls to skip that check
     */
    public RunSummary run(Path csv, Instant windowStart, Instant windowEnd) {
        JobParametersBuilder params = new JobParametersBuilder()
                .addString(SettlementReconciliationJobConfig.PARAM_INPUT_FILE, csv.toAbsolutePath().toString(), true);
        if (windowStart != null && windowEnd != null) {
            params.addString(SettlementReconciliationJobConfig.PARAM_WINDOW_START, windowStart.toString(), false);
            params.addString(SettlementReconciliationJobConfig.PARAM_WINDOW_END, windowEnd.toString(), false);
        }
        try {
            return summarize(jobLauncher.run(settlementReconciliationJob, params.toJobParameters()));
        } catch (JobInstanceAlreadyCompleteException e) {
            throw new AlreadyReconciledException(csv.getFileName().toString());
        } catch (JobExecutionAlreadyRunningException | JobRestartException e) {
            throw new IllegalStateException("Reconciliation job cannot start: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Reconciliation job failed to launch: " + e.getMessage(), e);
        }
    }

    static RunSummary summarize(JobExecution exec) {
        long staged = 0, skipped = 0;
        int mismatches = 0;
        for (StepExecution s : exec.getStepExecutions()) {
            if ("stageSettlementLines".equals(s.getStepName())) {
                staged = s.getWriteCount();
                skipped = s.getReadSkipCount();
            }
            if ("matchSettlementLines".equals(s.getStepName())) {
                mismatches = s.getExecutionContext().getInt("mismatches", 0);
            }
        }
        String fileId = exec.getExecutionContext().containsKey(SettlementReconciliationJobConfig.CTX_FILE_ID)
                ? exec.getExecutionContext().getString(SettlementReconciliationJobConfig.CTX_FILE_ID) : null;
        return new RunSummary(exec.getId(), exec.getStatus().name(), fileId, staged, skipped, mismatches,
                exec.getStatus() == BatchStatus.COMPLETED ? null : exec.getExitStatus().getExitDescription());
    }

    /** Safe local filename for an uploaded file: basename only, restricted characters, unique suffix. */
    public static String safeFilename(String original) {
        String base = original == null ? "settlement.csv" : Path.of(original).getFileName().toString();
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (!base.toLowerCase().endsWith(".csv")) base = base + ".csv";
        if (base.length() > 80) base = base.substring(base.length() - 80);
        return UUID.randomUUID().toString().substring(0, 8) + "_" + base;
    }
}
