package com.ledgerflow.investigator.controller;

import com.ledgerflow.investigator.domain.InvestigationProposal;
import com.ledgerflow.investigator.service.ReconciliationInvestigatorService;
import com.ledgerflow.recon.service.AdjustmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/investigator")
public class InvestigatorController {

    private final ReconciliationInvestigatorService investigatorService;
    private final AdjustmentService adjustmentService;

    public InvestigatorController(
            ReconciliationInvestigatorService investigatorService,
            AdjustmentService adjustmentService
    ) {
        this.investigatorService = investigatorService;
        this.adjustmentService = adjustmentService;
    }

    @PostMapping("/investigate/{reconItemId}")
    @PreAuthorize("hasAnyRole('OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<InvestigationProposal> investigate(@PathVariable("reconItemId") UUID reconItemId) {
        InvestigationProposal proposal = investigatorService.investigateMismatch(reconItemId);
        return ResponseEntity.ok(proposal);
    }

    @GetMapping("/proposals")
    @PreAuthorize("hasAnyRole('OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<List<InvestigationProposal>> getProposals() {
        return ResponseEntity.ok(investigatorService.getAllProposals());
    }

    @GetMapping("/proposals/{reconItemId}")
    @PreAuthorize("hasAnyRole('OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<InvestigationProposal> getProposalByReconItem(@PathVariable("reconItemId") UUID reconItemId) {
        return investigatorService.getProposalByReconItem(reconItemId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/approve-proposal")
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ResponseEntity<InvestigationProposal> approveProposal(
            @RequestParam("proposalId") UUID proposalId,
            @RequestParam(value = "notes", defaultValue = "Approved by Finance Admin") String notes
    ) {
        InvestigationProposal approved = adjustmentService.approveProposal(proposalId, notes);
        return ResponseEntity.ok(approved);
    }
}
