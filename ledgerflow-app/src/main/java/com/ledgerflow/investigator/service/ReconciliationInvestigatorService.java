package com.ledgerflow.investigator.service;

import com.ledgerflow.audit.AuditLogService;
import com.ledgerflow.investigator.domain.InvestigationProposal;
import com.ledgerflow.investigator.domain.InvestigationProposalEntity;
import com.ledgerflow.investigator.repository.InvestigatorProposalRepository;
import com.ledgerflow.investigator.tools.InvestigatorReadOnlyTools;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.repository.ReconItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Investigates a reconciliation mismatch and records an advisory proposal.
 *
 * <p>Flow: (1) gather evidence with read-only tools in a short read-only transaction;
 * (2) ask the AI model, outside any transaction; (3) validate its answer with
 * {@link AiProposalGuardrails}, falling back to {@link RuleBasedInvestigator};
 * (4) persist the proposal as PENDING_APPROVAL.
 *
 * <p>Core Principle 4: this service NEVER writes to the ledger or moves money.
 * Only {@code AdjustmentService.approveProposal} (ROLE_FINANCE_ADMIN) can do that.
 */
@Service
public class ReconciliationInvestigatorService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationInvestigatorService.class);

    private final InvestigatorReadOnlyTools tools;
    private final ReconItemRepository reconItemRepository;
    private final InvestigatorProposalRepository proposalRepository;
    private final AuditLogService auditLogService;
    private final RuleBasedInvestigator ruleBasedInvestigator;
    private final AiInvestigator aiInvestigator;
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate writeTx;

    public ReconciliationInvestigatorService(
            InvestigatorReadOnlyTools tools,
            ReconItemRepository reconItemRepository,
            InvestigatorProposalRepository proposalRepository,
            AuditLogService auditLogService,
            RuleBasedInvestigator ruleBasedInvestigator,
            AiInvestigator aiInvestigator,
            PlatformTransactionManager txManager
    ) {
        this.tools = tools;
        this.reconItemRepository = reconItemRepository;
        this.proposalRepository = proposalRepository;
        this.auditLogService = auditLogService;
        this.ruleBasedInvestigator = ruleBasedInvestigator;
        this.aiInvestigator = aiInvestigator;
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
        this.writeTx = new TransactionTemplate(txManager);
    }

    public InvestigationProposal investigateMismatch(UUID reconItemId) {
        log.info("Starting investigation for reconItemId={}", reconItemId);

        // 1. Evidence (short read-only transaction)
        InvestigationEvidence evidence = readOnlyTx.execute(status -> gatherEvidence(reconItemId));

        // 2 + 3. AI proposal (no transaction open), validated; rules as the fallback
        ProposalDraft draft = draftProposal(evidence);

        // 4. Persist (short write transaction)
        InvestigationProposalEntity saved = writeTx.execute(status -> {
            InvestigationProposalEntity entity = new InvestigationProposalEntity(
                    evidence.reconItemId(),
                    draft.rootCause(),
                    String.join(",", evidence.evidenceIds()),
                    draft.action().name(),
                    draft.recommendedAmount(),
                    evidence.currency(),
                    draft.rationale(),
                    draft.confidence()
            );
            entity.setProvenance(draft.generatedBy(), draft.guardrailNote());
            return proposalRepository.save(entity);
        });

        auditLogService.log(
                "AI_INVESTIGATOR",
                "SYSTEM_AI",
                "GENERATE_PROPOSAL",
                "INVESTIGATION_PROPOSAL",
                saved.getId().toString(),
                "reconItemId=" + evidence.reconItemId() + ", rootCause=" + draft.rootCause()
                        + ", proposedAction=" + draft.action() + ", generatedBy=" + draft.generatedBy()
        );

        return InvestigationProposal.from(saved);
    }

    private ProposalDraft draftProposal(InvestigationEvidence evidence) {
        ProposalDraft rules = ruleBasedInvestigator.investigate(evidence);
        if (!aiInvestigator.isAvailable()) {
            return rules;
        }
        try {
            Optional<AiInvestigator.AiAnswer> answer = aiInvestigator.investigate(evidence);
            if (answer.isEmpty()) {
                return rules;
            }
            AiProposalGuardrails.Verdict verdict = AiProposalGuardrails.check(answer.get(), evidence);
            if (verdict.accepted()) {
                return verdict.draft();
            }
            log.warn("AI proposal rejected by guardrails for reconItemId={}: {}",
                    evidence.reconItemId(), verdict.rejectionReason());
            return rules.withGuardrailNote("AI answer rejected: " + verdict.rejectionReason());
        } catch (RuntimeException e) {
            log.warn("AI investigator unavailable for reconItemId={}, using rules: {}",
                    evidence.reconItemId(), e.toString());
            return rules.withGuardrailNote("AI call failed: " + e.getClass().getSimpleName());
        }
    }

    private InvestigationEvidence gatherEvidence(UUID reconItemId) {
        ReconItem item = reconItemRepository.findById(reconItemId)
                .orElseThrow(() -> new IllegalArgumentException("Recon item not found: " + reconItemId));

        List<String> evidenceIds = new ArrayList<>();
        evidenceIds.add("recon_item:" + item.getId());
        evidenceIds.add("settlement_file:" + item.getSettlementFile().getId());

        String ref = item.getPaymentReference();
        String paymentStatus = null;
        int ledgerEntries = 0;
        int processorEvents = 0;

        if (ref != null && !ref.isBlank()) {
            Optional<InvestigatorReadOnlyTools.PaymentTrail> trail = tools.getPaymentTrail(ref);
            if (trail.isPresent()) {
                evidenceIds.add("payment:" + trail.get().id());
                paymentStatus = trail.get().status();
            }

            List<InvestigatorReadOnlyTools.LedgerEntryView> entries = tools.getLedgerEntries(ref);
            entries.forEach(e -> evidenceIds.add("journal_entry:" + e.entryId()));
            ledgerEntries = entries.size();

            List<InvestigatorReadOnlyTools.ProcessorEventView> events = tools.getProcessorEvents(ref);
            events.forEach(e -> evidenceIds.add("processor_event:" + e.id()));
            processorEvents = events.size();
        }

        return new InvestigationEvidence(
                item.getId(),
                item.getSettlementFile().getId(),
                item.getMismatchType(),
                ref,
                item.getBankAmount(),
                item.getLedgerAmount(),
                item.getCurrency(),
                paymentStatus,
                ledgerEntries,
                processorEvents,
                List.copyOf(evidenceIds)
        );
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
