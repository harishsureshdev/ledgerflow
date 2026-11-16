package com.ledgerflow.recon.batch;

import com.ledgerflow.recon.repository.SettlementFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;

/**
 * Every night (default 02:30 UTC) reconciles any new settlement CSVs dropped into the inbox
 * directory against the previous UTC business day's captured payments.
 *
 * <p>Only active when {@code ledgerflow.recon.inbox-dir} is set. Files already reconciled are
 * skipped (and the job itself refuses to run a file twice).
 */
@Component
@ConditionalOnProperty(name = "ledgerflow.recon.inbox-dir")
public class NightlySettlementScheduler {

    private static final Logger log = LoggerFactory.getLogger(NightlySettlementScheduler.class);

    private final SettlementReconciliationRunner runner;
    private final SettlementFileRepository settlementFileRepository;
    private final Path inbox;
    private final Clock clock;

    public NightlySettlementScheduler(
            SettlementReconciliationRunner runner,
            SettlementFileRepository settlementFileRepository,
            @Value("${ledgerflow.recon.inbox-dir}") String inboxDir
    ) {
        this.runner = runner;
        this.settlementFileRepository = settlementFileRepository;
        this.inbox = Path.of(inboxDir);
        this.clock = Clock.systemUTC();
    }

    @Scheduled(cron = "${ledgerflow.recon.cron:0 30 2 * * *}", zone = "UTC")
    public void reconcileInbox() {
        LocalDate businessDay = LocalDate.now(clock).minusDays(1);
        Instant start = businessDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = businessDay.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        for (Path csv : pendingFiles()) {
            try {
                var summary = runner.run(csv, start, end);
                log.info("Nightly reconciliation of {} for {}: {}", csv.getFileName(), businessDay, summary);
            } catch (SettlementReconciliationRunner.AlreadyReconciledException e) {
                log.debug("Skipping {}: already reconciled", csv.getFileName());
            } catch (RuntimeException e) {
                log.error("Nightly reconciliation of {} failed: {}", csv.getFileName(), e.getMessage());
            }
        }
    }

    List<Path> pendingFiles() {
        if (!Files.isDirectory(inbox)) {
            log.warn("Settlement inbox {} does not exist", inbox);
            return List.of();
        }
        try (Stream<Path> files = Files.list(inbox)) {
            return files.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".csv"))
                    // new files, plus files whose earlier run did not finish (the job restarts them)
                    .filter(p -> settlementFileRepository.findByFilename(p.getFileName().toString())
                            .map(f -> !"PROCESSED".equals(f.getStatus())).orElse(true))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("Cannot list settlement inbox {}: {}", inbox, e.getMessage());
            return List.of();
        }
    }
}
