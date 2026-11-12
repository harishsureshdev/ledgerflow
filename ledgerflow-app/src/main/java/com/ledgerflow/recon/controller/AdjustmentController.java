package com.ledgerflow.recon.controller;

import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.dto.ReconItemResponse;
import com.ledgerflow.recon.service.AdjustmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/adjustments")
public class AdjustmentController {

    private final AdjustmentService adjustmentService;

    public AdjustmentController(AdjustmentService adjustmentService) {
        this.adjustmentService = adjustmentService;
    }

    @PostMapping("/approve")
    @PreAuthorize("hasRole('FINANCE_ADMIN')")
    public ResponseEntity<ReconItemResponse> approveAdjustment(
            @RequestParam("reconItemId") UUID reconItemId,
            @RequestParam(value = "reason", defaultValue = "Reconciliation correction") String reason
    ) {
        ReconItem approved = adjustmentService.approveAdjustment(reconItemId, reason);
        return ResponseEntity.ok(ReconItemResponse.from(approved));
    }
}
