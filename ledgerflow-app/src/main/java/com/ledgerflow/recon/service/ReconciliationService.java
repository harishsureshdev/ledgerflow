package com.ledgerflow.recon.service;

import com.ledgerflow.audit.AuditLogService;
import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.domain.PaymentStatus;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.repository.SettlementFileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final SettlementFileRepository settlementFileRepository;
    private final ReconItemRepository reconItemRepository;
    private final PaymentRepository paymentRepository;
    private final AuditLogService auditLogService;

    public ReconciliationService(
            SettlementFileRepository settlementFileRepository,
            ReconItemRepository reconItemRepository,
            PaymentRepository paymentRepository,
            AuditLogService auditLogService
    ) {
        this.settlementFileRepository = settlementFileRepository;
        this.reconItemRepository = reconItemRepository;
        this.paymentRepository = paymentRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Executes reconciliation between an ingested settlement file and internal ledger/payments.
     * Identifies:
     * - DUPLICATE_SETTLEMENT: multiple bank lines for same payment
     * - MISSING_IN_LEDGER: bank settlement line with no internal payment record
     * - AMOUNT_MISMATCH: settlement line amount != ledger payment amount
     * - MISSING_IN_BANK: captured internal payment missing from bank settlement file
     */
    @Transactional
    public List<ReconItem> reconcileSettlement(String filename, List<SettlementLine> lines, Set<UUID> expectedPaymentIds) {
        log.info("Starting reconciliation for file '{}' with {} lines", filename, lines.size());

        long totalAmount = lines.stream().mapToLong(SettlementLine::amount).sum();
        String currency = lines.isEmpty() ? "USD" : lines.getFirst().currency();

        final SettlementFile settlementFile = settlementFileRepository.save(
                new SettlementFile(filename, lines.size(), totalAmount, currency)
        );

        List<ReconItem> reconItems = new ArrayList<>();
        Map<String, Integer> paymentFrequency = new HashMap<>();
        Set<UUID> foundPaymentIds = new HashSet<>();

        // Count frequencies to detect duplicates
        for (SettlementLine line : lines) {
            paymentFrequency.merge(line.paymentReference(), 1, Integer::sum);
        }

        Set<String> reportedDuplicates = new HashSet<>();

        // Process each line in settlement file
        for (SettlementLine line : lines) {
            String ref = line.paymentReference();

            // 1. DUPLICATE_SETTLEMENT
            if (paymentFrequency.getOrDefault(ref, 0) > 1 && reportedDuplicates.add(ref)) {
                reconItems.add(new ReconItem(
                        settlementFile,
                        MismatchType.DUPLICATE_SETTLEMENT,
                        ref,
                        line.amount(),
                        null,
                        line.currency()
                ));
            }

            // Look up payment in ledger
            UUID paymentId;
            try {
                paymentId = UUID.fromString(ref);
            } catch (Exception e) {
                paymentId = null;
            }

            Optional<Payment> paymentOpt = paymentId != null ? paymentRepository.findById(paymentId) : Optional.empty();

            if (paymentOpt.isEmpty()) {
                // 2. MISSING_IN_LEDGER
                reconItems.add(new ReconItem(
                        settlementFile,
                        MismatchType.MISSING_IN_LEDGER,
                        ref,
                        line.amount(),
                        null,
                        line.currency()
                ));
            } else {
                Payment payment = paymentOpt.get();
                foundPaymentIds.add(payment.getId());

                // 3. AMOUNT_MISMATCH
                if (payment.getAmount() != line.amount()) {
                    reconItems.add(new ReconItem(
                        settlementFile,
                        MismatchType.AMOUNT_MISMATCH,
                        ref,
                        line.amount(),
                        payment.getAmount(),
                        line.currency()
                    ));
                }
            }
        }

        // 4. MISSING_IN_BANK
        if (expectedPaymentIds != null) {
            for (UUID expectedId : expectedPaymentIds) {
                if (!foundPaymentIds.contains(expectedId)) {
                    paymentRepository.findById(expectedId).ifPresent(p -> {
                        reconItems.add(new ReconItem(
                                settlementFile,
                                MismatchType.MISSING_IN_BANK,
                                expectedId.toString(),
                                null,
                                p.getAmount(),
                                p.getCurrency()
                        ));
                    });
                }
            }
        }

        List<ReconItem> saved = reconItemRepository.saveAll(reconItems);
        auditLogService.log(
                "RECON_SYSTEM",
                "SYSTEM",
                "RECONCILIATION_RUN",
                "SETTLEMENT_FILE",
                settlementFile.getId().toString(),
                "file=" + filename + ", mismatches=" + saved.size()
        );

        return saved;
    }
}
