package com.ledgerflow.recon;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.service.PaymentService;
import com.ledgerflow.recon.batch.SettlementReconciliationRunner;
import com.ledgerflow.recon.batch.SettlementReconciliationRunner.RunSummary;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.repository.SettlementFileRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Spring Batch nightly reconciliation job, end to end against real Postgres. */
class SettlementReconciliationJobIntegrationTest extends AbstractIntegrationTest {

    private static final String HEADER = "line_id,payment_reference,amount_minor,currency,status";

    @TempDir Path tmp;

    @Autowired private SettlementReconciliationRunner runner;
    @Autowired private PaymentService paymentService;
    @Autowired private ReconItemRepository reconItemRepository;
    @Autowired private SettlementFileRepository settlementFileRepository;
    @Autowired private JournalEntryRepository journalEntryRepository;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void journalStillBalances() {
        assertThat(journalEntryRepository.sumAllEntriesAmount()).isZero();
    }

    private UUID pay(long amount) {
        return paymentService.processPayment("merch_" + UUID.randomUUID(), "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_" + UUID.randomUUID(), amount, "USD", "tok_visa_4242")).response().id();
    }

    private Path csv(String name, List<String> rows) throws IOException {
        List<String> all = new ArrayList<>();
        all.add(HEADER);
        all.addAll(rows);
        return Files.write(tmp.resolve(name + "_" + UUID.randomUUID() + ".csv"), all);
    }

    private Map<MismatchType, List<ReconItem>> itemsOf(RunSummary s) {
        return reconItemRepository.findBySettlementFileId(UUID.fromString(s.settlementFileId())).stream()
                .collect(Collectors.groupingBy(ReconItem::getMismatchType));
    }

    @Test
    @DisplayName("Job flags exactly the injected mismatches, skips malformed lines, and records file totals")
    void flagsInjectedMismatches() throws Exception {
        Instant windowStart = Instant.now().minusSeconds(1);
        UUID matched = pay(1000L);
        UUID wrongAmount = pay(2000L);
        UUID duplicated = pay(3000L);
        UUID omitted = pay(4000L);
        Instant windowEnd = Instant.now().plusSeconds(1);
        String ghost = "ghost_" + UUID.randomUUID();

        Path file = csv("settlement", List.of(
                "L1," + matched + ",1000,USD,SETTLED",
                "L2," + wrongAmount + ",2500,USD,SETTLED",   // AMOUNT_MISMATCH (+500)
                "L3," + duplicated + ",3000,USD,SETTLED",
                "L4," + duplicated + ",3000,USD,SETTLED",    // DUPLICATE_SETTLEMENT
                "L5," + ghost + ",4999,USD,SETTLED",          // MISSING_IN_LEDGER
                "L6," + matched + ",12.50,USD,SETTLED",       // malformed: decimal amount -> skipped
                "L7,only,three"                               // malformed: wrong column count -> skipped
                // `omitted` is not in the file                  MISSING_IN_BANK
        ));

        RunSummary s = runner.run(file, windowStart, windowEnd);

        assertThat(s.status()).isEqualTo("COMPLETED");
        assertThat(s.linesStaged()).isEqualTo(5);
        assertThat(s.linesSkipped()).isEqualTo(2);
        assertThat(s.mismatches()).isEqualTo(4);

        var items = itemsOf(s);
        assertThat(items.keySet()).containsExactlyInAnyOrder(MismatchType.values());
        assertThat(items.values()).allMatch(l -> l.size() == 1);
        ReconItem amt = items.get(MismatchType.AMOUNT_MISMATCH).getFirst();
        assertThat(amt.getPaymentReference()).isEqualTo(wrongAmount.toString());
        assertThat(amt.getBankAmount()).isEqualTo(2500L);
        assertThat(amt.getLedgerAmount()).isEqualTo(2000L);
        assertThat(items.get(MismatchType.DUPLICATE_SETTLEMENT).getFirst().getPaymentReference()).isEqualTo(duplicated.toString());
        assertThat(items.get(MismatchType.MISSING_IN_LEDGER).getFirst().getPaymentReference()).isEqualTo(ghost);
        assertThat(items.get(MismatchType.MISSING_IN_BANK).getFirst().getPaymentReference()).isEqualTo(omitted.toString());

        SettlementFile f = settlementFileRepository.findById(UUID.fromString(s.settlementFileId())).orElseThrow();
        assertThat(f.getStatus()).isEqualTo("PROCESSED");
        assertThat(f.getTotalRecords()).isEqualTo(5);
        assertThat(f.getTotalAmount()).isEqualTo(1000 + 2500 + 3000 + 3000 + 4999);
    }

    @Test
    @DisplayName("The same settlement file cannot be reconciled twice (no duplicate recon items)")
    void sameFileTwiceIsRejected() throws Exception {
        Path file = csv("once", List.of("L1,ghost_" + UUID.randomUUID() + ",100,USD,SETTLED"));
        RunSummary first = runner.run(file, null, null);
        long itemsAfterFirst = reconItemRepository.count();

        assertThatThrownBy(() -> runner.run(file, null, null))
                .isInstanceOf(SettlementReconciliationRunner.AlreadyReconciledException.class);
        assertThat(first.mismatches()).isEqualTo(1);
        assertThat(reconItemRepository.count()).isEqualTo(itemsAfterFirst);
    }

    @Test
    @DisplayName("Large file is processed in chunks of 500")
    void largeFileIsChunked() throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 1201; i++) {
            rows.add("L" + i + ",ghost_" + i + "_" + UUID.randomUUID() + ",100,USD,SETTLED");
        }
        RunSummary s = runner.run(csv("big", rows), null, null);

        assertThat(s.status()).isEqualTo("COMPLETED");
        assertThat(s.linesStaged()).isEqualTo(1201);
        assertThat(s.mismatches()).isEqualTo(1201);
        Long commits = jdbc.queryForObject(
                "SELECT commit_count FROM batch_step_execution WHERE job_execution_id = ? AND step_name = 'stageSettlementLines'",
                Long.class, s.jobExecutionId());
        assertThat(commits).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("Too many bad lines fails the job; after the file is fixed, re-running restarts it and completes")
    void failsOnGarbageThenRestarts() throws Exception {
        Path file = tmp.resolve("restart_" + UUID.randomUUID() + ".csv");
        List<String> garbage = new ArrayList<>(List.of(HEADER));
        for (int i = 0; i < 150; i++) garbage.add("broken line " + i);
        Files.write(file, garbage);

        RunSummary failed = runner.run(file, null, null);
        assertThat(failed.status()).isEqualTo("FAILED");

        Files.write(file, List.of(HEADER, "L1,ghost_" + UUID.randomUUID() + ",700,USD,SETTLED"));
        RunSummary restarted = runner.run(file, null, null);

        assertThat(restarted.status()).isEqualTo("COMPLETED");
        assertThat(restarted.settlementFileId()).isEqualTo(failed.settlementFileId());
        assertThat(restarted.mismatches()).isEqualTo(1);
    }
}
