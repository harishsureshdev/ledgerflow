package com.ledgerflow.recon.service;

import com.ledgerflow.audit.AuditLogService;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.repository.SettlementFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.*;

/**
 * Reconciliation engine: compares a bank settlement file against the ledger.
 *
 * <p>Lines are first staged into {@code settlement_lines} (by the Spring Batch job in chunks,
 * or in one go for ad-hoc runs), then matched with set-based SQL:
 * <ul>
 *   <li>DUPLICATE_SETTLEMENT: a payment reference appears on more than one line</li>
 *   <li>MISSING_IN_LEDGER: a line whose reference matches no payment</li>
 *   <li>AMOUNT_MISMATCH: a line whose amount differs from the payment's amount</li>
 *   <li>MISSING_IN_BANK: an expected payment that appears on no line</li>
 * </ul>
 * Each mismatch is reported once per reference. Matching is idempotent per file: re-running it
 * on an already PROCESSED file returns the existing items instead of creating duplicates.
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final SettlementFileRepository settlementFileRepository;
    private final ReconItemRepository reconItemRepository;
    private final AuditLogService auditLogService;
    private final JdbcTemplate jdbc;

    public ReconciliationService(
            SettlementFileRepository settlementFileRepository,
            ReconItemRepository reconItemRepository,
            AuditLogService auditLogService,
            JdbcTemplate jdbc
    ) {
        this.settlementFileRepository = settlementFileRepository;
        this.reconItemRepository = reconItemRepository;
        this.auditLogService = auditLogService;
        this.jdbc = jdbc;
    }

    /** Ad-hoc run (demo endpoint, tests): register, stage and match a file in one transaction. */
    @Transactional
    public List<ReconItem> reconcileSettlement(String filename, List<SettlementLine> lines, Set<UUID> expectedPaymentIds) {
        log.info("Starting reconciliation for file '{}' with {} lines", filename, lines.size());
        SettlementFile file = registerFile(filename);
        List<StagedSettlementLine> staged = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            staged.add(new StagedSettlementLine(i + 1, lines.get(i)));
        }
        stageLines(file.getId(), staged);
        return matchStagedFile(file.getId(), ExpectedPayments.ids(expectedPaymentIds));
    }

    /** Creates the settlement file record, or returns it if this file was already registered (job restart). */
    @Transactional
    public SettlementFile registerFile(String filename) {
        return settlementFileRepository.findByFilename(filename)
                // flush now: staged lines are inserted with plain JDBC and reference this row
                .orElseGet(() -> settlementFileRepository.saveAndFlush(SettlementFile.staging(filename)));
    }

    /** Inserts lines; re-inserting the same (file, line number) is a no-op, so restarted chunks are safe. */
    @Transactional
    public void stageLines(UUID fileId, List<StagedSettlementLine> lines) {
        jdbc.batchUpdate("""
                INSERT INTO settlement_lines
                    (settlement_file_id, line_no, line_id, payment_reference, amount, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (settlement_file_id, line_no) DO NOTHING
                """,
                lines, 500, (ps, s) -> {
                    ps.setObject(1, fileId);
                    ps.setInt(2, s.lineNo());
                    ps.setString(3, s.line().lineId());
                    ps.setString(4, s.line().paymentReference());
                    ps.setLong(5, s.line().amount());
                    ps.setString(6, s.line().currency());
                    ps.setString(7, s.line().status());
                });
    }

    @Transactional
    public List<ReconItem> matchStagedFile(UUID fileId, ExpectedPayments expected) {
        SettlementFile file = settlementFileRepository.findById(fileId)
                .orElseThrow(() -> new IllegalArgumentException("Settlement file not found: " + fileId));
        if ("PROCESSED".equals(file.getStatus())) {
            log.info("Settlement file {} already reconciled; returning existing items", file.getFilename());
            return reconItemRepository.findBySettlementFileId(fileId);
        }

        List<ReconItem> items = new ArrayList<>();

        // 1. DUPLICATE_SETTLEMENT
        jdbc.query("""
                SELECT payment_reference, MIN(amount) AS amount, MIN(currency) AS currency
                FROM settlement_lines WHERE settlement_file_id = ?
                GROUP BY payment_reference HAVING COUNT(*) > 1
                """, rs -> {
            items.add(new ReconItem(file, MismatchType.DUPLICATE_SETTLEMENT, rs.getString(1),
                    rs.getLong(2), null, rs.getString(3)));
        }, fileId);

        // 2. MISSING_IN_LEDGER
        jdbc.query("""
                SELECT l.payment_reference, MIN(l.amount), MIN(l.currency)
                FROM settlement_lines l
                LEFT JOIN payments p ON p.id::text = l.payment_reference
                WHERE l.settlement_file_id = ? AND p.id IS NULL
                GROUP BY l.payment_reference
                """, rs -> {
            items.add(new ReconItem(file, MismatchType.MISSING_IN_LEDGER, rs.getString(1),
                    rs.getLong(2), null, rs.getString(3)));
        }, fileId);

        // 3. AMOUNT_MISMATCH
        jdbc.query("""
                SELECT l.payment_reference, MIN(l.amount), p.amount, p.currency
                FROM settlement_lines l
                JOIN payments p ON p.id::text = l.payment_reference
                WHERE l.settlement_file_id = ? AND l.amount <> p.amount
                GROUP BY l.payment_reference, p.amount, p.currency
                """, rs -> {
            items.add(new ReconItem(file, MismatchType.AMOUNT_MISMATCH, rs.getString(1),
                    rs.getLong(2), rs.getLong(3), rs.getString(4)));
        }, fileId);

        // 4. MISSING_IN_BANK
        String notInFile = "AND NOT EXISTS (SELECT 1 FROM settlement_lines l "
                + "WHERE l.settlement_file_id = ? AND l.payment_reference = p.id::text)";
        if (expected.isWindow()) {
            jdbc.query("SELECT p.id, p.amount, p.currency FROM payments p "
                            + "WHERE p.status IN ('CAPTURED', 'SETTLED') AND p.created_at >= ? AND p.created_at < ? " + notInFile,
                    rs -> {
                        items.add(new ReconItem(file, MismatchType.MISSING_IN_BANK, rs.getString(1),
                                null, rs.getLong(2), rs.getString(3)));
                    },
                    Timestamp.from(expected.windowStart()), Timestamp.from(expected.windowEnd()), fileId);
        } else if (!expected.paymentIds().isEmpty()) {
            UUID[] ids = expected.paymentIds().toArray(UUID[]::new);
            jdbc.query(con -> {
                var ps = con.prepareStatement(
                        "SELECT p.id, p.amount, p.currency FROM payments p WHERE p.id = ANY(?) " + notInFile);
                ps.setArray(1, con.createArrayOf("uuid", ids));
                ps.setObject(2, fileId);
                return ps;
            }, rs -> {
                items.add(new ReconItem(file, MismatchType.MISSING_IN_BANK, rs.getString(1),
                        null, rs.getLong(2), rs.getString(3)));
            });
        }

        // File totals come from what was actually staged
        Map<String, Object> totals = jdbc.queryForMap("""
                SELECT COUNT(*) AS n, COALESCE(SUM(amount), 0) AS total, MIN(currency) AS currency
                FROM settlement_lines WHERE settlement_file_id = ?
                """, fileId);
        file.markProcessed(((Number) totals.get("n")).intValue(), ((Number) totals.get("total")).longValue(),
                (String) totals.get("currency"));
        settlementFileRepository.save(file);

        List<ReconItem> saved = reconItemRepository.saveAll(items);
        auditLogService.log(
                "RECON_SYSTEM",
                "SYSTEM",
                "RECONCILIATION_RUN",
                "SETTLEMENT_FILE",
                fileId.toString(),
                "file=" + file.getFilename() + ", lines=" + totals.get("n") + ", mismatches=" + saved.size()
        );
        log.info("Reconciled '{}': {} lines, {} mismatches", file.getFilename(), totals.get("n"), saved.size());
        return saved;
    }
}
