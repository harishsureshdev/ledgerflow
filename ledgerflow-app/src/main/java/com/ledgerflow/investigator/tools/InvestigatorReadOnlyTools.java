package com.ledgerflow.investigator.tools;

import com.ledgerflow.ledger.domain.JournalEntry;
import com.ledgerflow.ledger.domain.JournalTransaction;
import com.ledgerflow.ledger.repository.JournalEntryRepository;
import com.ledgerflow.ledger.repository.JournalTransactionRepository;
import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.processor.webhook.ProcessorEventRecord;
import com.ledgerflow.processor.webhook.ProcessorEventRepository;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.repository.ReconItemRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Core Principle 4 (Non-Negotiable):
 * AI NEVER MOVES MONEY.
 * The reconciliation investigator has STRICTLY READ-ONLY tools.
 * There are no write, insert, update, or delete methods exposed here.
 */
@Component
public class InvestigatorReadOnlyTools {

    private final PaymentRepository paymentRepository;
    private final JournalTransactionRepository transactionRepository;
    private final JournalEntryRepository entryRepository;
    private final ProcessorEventRepository processorEventRepository;
    private final ReconItemRepository reconItemRepository;

    public InvestigatorReadOnlyTools(
            PaymentRepository paymentRepository,
            JournalTransactionRepository transactionRepository,
            JournalEntryRepository entryRepository,
            ProcessorEventRepository processorEventRepository,
            ReconItemRepository reconItemRepository
    ) {
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.processorEventRepository = processorEventRepository;
        this.reconItemRepository = reconItemRepository;
    }

    public record PaymentTrail(
            UUID id,
            String merchantId,
            String customerId,
            long amount,
            String currency,
            String status,
            String paymentMethodToken,
            Instant createdAt
    ) {}

    public record LedgerEntryView(
            UUID entryId,
            String accountNumber,
            long amount,
            int entryOrder,
            Instant createdAt
    ) {}

    public record ProcessorEventView(
            UUID id,
            String processorEventId,
            String eventType,
            String status,
            Instant createdAt
    ) {}

    public record SettlementLineView(
            UUID reconItemId,
            UUID settlementFileId,
            String mismatchType,
            String paymentReference,
            Long bankAmount,
            Long ledgerAmount,
            String currency,
            String status
    ) {}

    /**
     * Tool 1: getPaymentTrail
     * Read-only lookup of payment state and lifecycle.
     */
    @Transactional(readOnly = true)
    public Optional<PaymentTrail> getPaymentTrail(String referenceOrId) {
        try {
            UUID id = UUID.fromString(referenceOrId);
            return paymentRepository.findById(id).map(p -> new PaymentTrail(
                    p.getId(),
                    p.getMerchantId(),
                    p.getCustomerId(),
                    p.getAmount(),
                    p.getCurrency(),
                    p.getStatus().name(),
                    p.getPaymentToken(),
                    p.getCreatedAt()
            ));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * Tool 2: getLedgerEntries
     * Read-only inspection of balanced double-entry journal records.
     */
    @Transactional(readOnly = true)
    public List<LedgerEntryView> getLedgerEntries(String paymentReference) {
        // Payments are journaled under "PAYMENT-<paymentId>"; accept either form.
        Optional<JournalTransaction> txOpt = transactionRepository.findByReferenceWithEntries(paymentReference)
                .or(() -> transactionRepository.findByReferenceWithEntries("PAYMENT-" + paymentReference));
        if (txOpt.isEmpty()) {
            return Collections.emptyList();
        }
        JournalTransaction tx = txOpt.get();
        List<JournalEntry> entries = entryRepository.findByTransactionIdOrderByEntryOrderAsc(tx.getId());
        return entries.stream()
                .map(e -> new LedgerEntryView(
                        e.getId(),
                        e.getAccount().getAccountNumber(),
                        e.getAmount(),
                        e.getEntryOrder(),
                        e.getCreatedAt()
                ))
                .toList();
    }

    /**
     * Tool 3: getProcessorEvents
     * Read-only inspection of processor webhook records.
     */
    @Transactional(readOnly = true)
    public List<ProcessorEventView> getProcessorEvents(String paymentReference) {
        List<ProcessorEventRecord> events = processorEventRepository.findByPayloadContaining(paymentReference);
        return events.stream()
                .map(e -> new ProcessorEventView(
                        e.getId(),
                        e.getProcessorEventId(),
                        e.getEventType(),
                        e.getStatus(),
                        e.getCreatedAt()
                ))
                .toList();
    }

    /**
     * Tool 4: getSettlementLine
     * Read-only inspection of the bank settlement feed line for a reconciliation discrepancy.
     */
    @Transactional(readOnly = true)
    public Optional<SettlementLineView> getSettlementLine(UUID reconItemId) {
        return reconItemRepository.findById(reconItemId).map(item -> new SettlementLineView(
                item.getId(),
                item.getSettlementFile().getId(),
                item.getMismatchType().name(),
                item.getPaymentReference(),
                item.getBankAmount(),
                item.getLedgerAmount(),
                item.getCurrency(),
                item.getStatus()
        ));
    }
}
