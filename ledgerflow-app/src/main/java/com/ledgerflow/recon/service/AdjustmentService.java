package com.ledgerflow.recon.service;

import com.ledgerflow.audit.AuditLogService;
import com.ledgerflow.common.domain.Money;
import com.ledgerflow.investigator.domain.InvestigationProposal;
import com.ledgerflow.investigator.domain.InvestigationProposalEntity;
import com.ledgerflow.investigator.repository.InvestigatorProposalRepository;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.domain.TransactionType;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.ledger.service.LedgerService;
import com.ledgerflow.ledger.service.PostTransactionCommand;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.repository.ReconItemRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AdjustmentService {

    private final ReconItemRepository reconItemRepository;
    private final LedgerService ledgerService;
    private final AccountProvisioningService accountProvisioningService;
    private final InvestigatorProposalRepository proposalRepository;
    private final AuditLogService auditLogService;

    public AdjustmentService(
            ReconItemRepository reconItemRepository,
            LedgerService ledgerService,
            AccountProvisioningService accountProvisioningService,
            InvestigatorProposalRepository proposalRepository,
            AuditLogService auditLogService
    ) {
        this.reconItemRepository = reconItemRepository;
        this.ledgerService = ledgerService;
        this.accountProvisioningService = accountProvisioningService;
        this.proposalRepository = proposalRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Approves and executes a compensating adjustment for a reconciliation mismatch.
     *
     * Core Design Principle 4 (Non-Negotiable):
     * Strictly enforced in the SERVICE LAYER: Only a user with ROLE_FINANCE_ADMIN
     * can execute financial adjustments. Prompts or non-admin roles can NEVER move money.
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @Transactional
    public ReconItem approveAdjustment(UUID reconItemId, String reason) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_FINANCE_ADMIN"))) {
            throw new AccessDeniedException("Service Layer Invariant: Only ROLE_FINANCE_ADMIN is authorized to approve ledger adjustments");
        }

        ReconItem item = reconItemRepository.findById(reconItemId)
                .orElseThrow(() -> new IllegalArgumentException("Reconciliation item not found: " + reconItemId));

        item.setStatus("RESOLVED");
        reconItemRepository.save(item);

        auditLogService.log(
                auth.getName(),
                "FINANCE_ADMIN",
                "APPROVE_ADJUSTMENT",
                "RECON_ITEM",
                reconItemId.toString(),
                "reason=" + reason + ", mismatch=" + item.getMismatchType()
        );

        return item;
    }

    /**
     * Approves an AI-generated InvestigationProposal and posts a balanced compensating
     * entry in the ledger if an amount adjustment is recommended.
     */
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    @Transactional
    public InvestigationProposal approveProposal(UUID proposalId, String notes) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_FINANCE_ADMIN"))) {
            throw new AccessDeniedException("Service Layer Invariant: Only ROLE_FINANCE_ADMIN is authorized to approve AI proposals");
        }

        InvestigationProposalEntity proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));

        ReconItem item = reconItemRepository.findById(proposal.getReconItemId())
                .orElseThrow(() -> new IllegalArgumentException("Recon item not found for proposal: " + proposal.getReconItemId()));

        // Execute compensating balanced entry if amount adjustment is required
        if (proposal.getRecommendedAmount() != null && proposal.getRecommendedAmount() > 0) {
            Account clearing = accountProvisioningService.getOrCreateSettlementAccount(proposal.getCurrency());
            Account platform = accountProvisioningService.getOrCreateMerchantAccount("PLATFORM_ADJUSTMENTS", proposal.getCurrency());

            long adjAmount = proposal.getRecommendedAmount();
            String adjRef = "adj_" + proposal.getId().toString().substring(0, 8);

            // Balanced journal entry: leg1 + leg2 = 0
            ledgerService.postTransaction(new PostTransactionCommand(
                    adjRef,
                    TransactionType.ADJUSTMENT,
                    "Compensating adjustment for recon item " + item.getId() + " - " + notes,
                    List.of(
                            new PostTransactionCommand.PostEntryCommand(clearing.getId(), new Money(adjAmount, proposal.getCurrency())),
                            new PostTransactionCommand.PostEntryCommand(platform.getId(), new Money(-adjAmount, proposal.getCurrency()))
                    )
            ));
        }

        proposal.setStatus("APPROVED");
        proposalRepository.save(proposal);

        item.setStatus("RESOLVED");
        reconItemRepository.save(item);

        auditLogService.log(
                auth.getName(),
                "FINANCE_ADMIN",
                "APPROVE_AI_PROPOSAL",
                "INVESTIGATION_PROPOSAL",
                proposalId.toString(),
                "action=" + proposal.getProposedAction() + ", notes=" + notes
        );

        return InvestigationProposal.from(proposal);
    }
}
