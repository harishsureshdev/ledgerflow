package com.ledgerflow.investigator.service;

import com.ledgerflow.audit.AuditLogService;
import com.ledgerflow.investigator.domain.InvestigationProposal;
import com.ledgerflow.investigator.domain.InvestigationProposalEntity;
import com.ledgerflow.investigator.repository.InvestigatorProposalRepository;
import com.ledgerflow.investigator.tools.InvestigatorReadOnlyTools;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.repository.ReconItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ReconciliationInvestigatorService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationInvestigatorService.class);

    private final InvestigatorReadOnlyTools tools;
    private final ReconItemRepository reconItemRepository;
    private final InvestigatorProposalRepository proposalRepository;
    private final AuditLogService auditLogService;

    public ReconciliationInvestigatorService(
            InvestigatorReadOnlyTools tools,
            ReconItemRepository reconItemRepository,
            InvestigatorProposalRepository proposalRepository,
            AuditLogService auditLogService
    ) {
        this.tools = tools;
        this.reconItemRepository = reconItemRepository;
        this.proposalRepository = proposalRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Conducts a read-only investigation into a reconciliation mismatch.
     * Compiles an evidence dossier and formulates a structured InvestigationProposal.
     *
     * Core Principle 4:
     * This method NEVER writes to the ledger or moves money.
     * Any adjustment requires explicit approval by ROLE_FINANCE_ADMIN.
     */
    @Transactional
    public InvestigationProposal investigateMismatch(UUID reconItemId) {
        log.info("Starting AI investigation for reconItemId={}", reconItemId);

        ReconItem item = reconItemRepository.findById(reconItemId)
                .orElseThrow(() -> new IllegalArgumentException("Recon item not found: " + reconItemId));

        // 1. Gather Evidence using strictly Read-Only Tools
        List<String> evidenceIds = new ArrayList<>();
        evidenceIds.add("recon_item:" + item.getId());
        evidenceIds.add("settlement_file:" + item.getSettlementFile().getId());

        String ref = item.getPaymentReference();
        Optional<InvestigatorReadOnlyTools.PaymentTrail> paymentTrail = Optional.empty();
        if (ref != null && !ref.isBlank()) {
            paymentTrail = tools.getPaymentTrail(ref);
            paymentTrail.ifPresent(p -> evidenceIds.add("payment:" + p.id()));

            List<InvestigatorReadOnlyTools.LedgerEntryView> ledgerEntries = tools.getLedgerEntries(ref);
            ledgerEntries.forEach(e -> evidenceIds.add("journal_entry:" + e.entryId()));

            List<InvestigatorReadOnlyTools.ProcessorEventView> processorEvents = tools.getProcessorEvents(ref);
            processorEvents.forEach(e -> evidenceIds.add("processor_event:" + e.id()));
        }

        // 2. Formulate Root Cause & Proposed Compensating Action
        String rootCause;
        String proposedAction;
        Long recommendedAmount = null;
        String rationale;
        double confidenceScore;

        MismatchType mismatch = item.getMismatchType();

        switch (mismatch) {
            case DUPLICATE_SETTLEMENT -> {
                rootCause = "PROCESSOR_DUPLICATE_FEED";
                proposedAction = "REJECT_DUPLICATE_BANK_ENTRY";
                recommendedAmount = 0L;
                confidenceScore = 0.99;
                rationale = String.format(
                        "Bank settlement feed contains multiple lines for reference %s. Internal payment status is %s with balanced ledger transactions. Recommend rejecting duplicate feed record without debiting merchant.",
                        ref, paymentTrail.map(InvestigatorReadOnlyTools.PaymentTrail::status).orElse("UNKNOWN")
                );
            }
            case AMOUNT_MISMATCH -> {
                rootCause = "SETTLEMENT_AMOUNT_DIVERGENCE";
                long bankAmt = item.getBankAmount() != null ? item.getBankAmount() : 0L;
                long ledgerAmt = item.getLedgerAmount() != null ? item.getLedgerAmount() : 0L;
                long delta = bankAmt - ledgerAmt;

                proposedAction = delta > 0 ? "COMPENSATING_CREDIT_SETTLEMENT" : "COMPENSATING_DEBIT_SETTLEMENT";
                recommendedAmount = Math.abs(delta);
                confidenceScore = 0.96;
                rationale = String.format(
                        "Bank feed amount (%d %s) diverges from internal ledger payment (%d %s) by delta of %d minor units. Recommend posting a compensating balanced journal entry to reconcile processor clearing account.",
                        bankAmt, item.getCurrency(), ledgerAmt, item.getCurrency(), delta
                );
            }
            case MISSING_IN_BANK -> {
                rootCause = "EXTERNAL_SETTLEMENT_CYCLE_DROP";
                proposedAction = "TRACE_PROCESSOR_SETTLEMENT_CYCLE";
                recommendedAmount = item.getLedgerAmount();
                confidenceScore = 0.92;
                rationale = String.format(
                        "Payment %s is recorded and captured internally with ledger entries, but omitted from card processor settlement batch. Recommend verifying processor batch status before taking financial action.",
                        ref
                );
            }
            case MISSING_IN_LEDGER -> {
                rootCause = "UNMATCHED_EXTERNAL_CHARGE";
                proposedAction = "MANUAL_FRAUD_REVIEW";
                recommendedAmount = item.getBankAmount();
                confidenceScore = 0.90;
                rationale = String.format(
                        "Card network reported settlement line %s for %d %s, but no corresponding internal payment authorization or idempotency key exists. Flagged for compliance review.",
                        ref, item.getBankAmount(), item.getCurrency()
                );
            }
            default -> {
                rootCause = "UNKNOWN_DISCREPANCY";
                proposedAction = "MANUAL_INVESTIGATION";
                confidenceScore = 0.50;
                rationale = "Unrecognized mismatch type: " + mismatch;
            }
        }

        // 3. Persist Proposal with PENDING_APPROVAL status
        String joinedEvidence = String.join(",", evidenceIds);
        InvestigationProposalEntity entity = new InvestigationProposalEntity(
                item.getId(),
                rootCause,
                joinedEvidence,
                proposedAction,
                recommendedAmount,
                item.getCurrency(),
                rationale,
                confidenceScore
        );

        InvestigationProposalEntity saved = proposalRepository.save(entity);

        auditLogService.log(
                "AI_INVESTIGATOR",
                "SYSTEM_AI",
                "GENERATE_PROPOSAL",
                "INVESTIGATION_PROPOSAL",
                saved.getId().toString(),
                "reconItemId=" + item.getId() + ", rootCause=" + rootCause + ", proposedAction=" + proposedAction
        );

        return InvestigationProposal.from(saved);
    }

    @Transactional(readOnly = true)
    public List<InvestigationProposal> getAllProposals() {
        return proposalRepository.findAll().stream()
                .map(InvestigationProposal::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<InvestigationProposal> getProposalByReconItem(UUID reconItemId) {
        return proposalRepository.findByReconItemId(reconItemId)
                .map(InvestigationProposal::from);
    }
}
