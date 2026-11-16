package com.ledgerflow.recon.controller;

import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.dto.ReconItemResponse;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.service.ReconciliationService;
import com.ledgerflow.recon.service.SettlementLine;
import com.ledgerflow.recon.batch.SettlementReconciliationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/recon")
public class ReconController {

    private final ReconItemRepository reconItemRepository;
    private final ReconciliationService reconciliationService;
    private final PaymentRepository paymentRepository;

    private final SettlementReconciliationRunner reconciliationRunner;
    private final Path workDir;

    public ReconController(
            ReconItemRepository reconItemRepository,
            ReconciliationService reconciliationService,
            PaymentRepository paymentRepository,
            SettlementReconciliationRunner reconciliationRunner,
            @Value("${ledgerflow.recon.work-dir:${java.io.tmpdir}/ledgerflow-recon}") String workDir
    ) {
        this.reconItemRepository = reconItemRepository;
        this.reconciliationService = reconciliationService;
        this.paymentRepository = paymentRepository;
        this.reconciliationRunner = reconciliationRunner;
        this.workDir = Path.of(workDir).toAbsolutePath().normalize();
    }

    @GetMapping("/items")
    @PreAuthorize("hasAnyRole('OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<List<ReconItemResponse>> getReconItems() {
        List<ReconItemResponse> list = reconItemRepository.findAll().stream()
                .map(ReconItemResponse::from)
                .toList();
        return ResponseEntity.ok(list);
    }

    /**
     * Upload a bank settlement CSV and run the Spring Batch reconciliation job on it now
     * (the same job the nightly scheduler runs). Header: line_id,payment_reference,amount_minor,currency,status.
     * Optional windowStart/windowEnd (ISO-8601) enable MISSING_IN_BANK detection for payments in that window.
     */
    @PostMapping(value = "/settlement-files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<SettlementReconciliationRunner.RunSummary> uploadSettlementFile(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "windowStart", required = false) Instant windowStart,
            @RequestParam(value = "windowEnd", required = false) Instant windowEnd
    ) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Settlement file is empty");
        }
        Files.createDirectories(workDir);
        Path target = workDir.resolve(SettlementReconciliationRunner.safeFilename(file.getOriginalFilename())).normalize();
        if (!target.startsWith(workDir)) {
            throw new IllegalArgumentException("Invalid filename");
        }
        file.transferTo(target);
        return ResponseEntity.ok(reconciliationRunner.run(target, windowStart, windowEnd));
    }

    /**
     * Demo helper endpoint to simulate a nightly settlement file with injected mismatches.
     * Useful for live demonstrations and testing the reconciliation queue and AI investigator.
     */
    @PostMapping("/simulate")
    @PreAuthorize("hasAnyRole('OPS_AGENT', 'FINANCE_ADMIN')")
    public ResponseEntity<List<ReconItemResponse>> simulateSettlement() {
        List<Payment> payments = paymentRepository.findAll();
        List<SettlementLine> lines = new ArrayList<>();
        Set<UUID> expectedIds = new HashSet<>();

        String batchTag = UUID.randomUUID().toString().substring(0, 8);
        String filename = "settlement_bank_feed_" + LocalDate.now() + "_" + batchTag + ".csv";

        if (!payments.isEmpty()) {
            Payment p1 = payments.getFirst();
            expectedIds.add(p1.getId());
            // 1. Matched payment
            lines.add(new SettlementLine("LINE_" + UUID.randomUUID(), p1.getId().toString(), p1.getAmount(), p1.getCurrency(), "SETTLED"));

            if (payments.size() > 1) {
                Payment p2 = payments.get(1);
                expectedIds.add(p2.getId());
                // 2. AMOUNT_MISMATCH
                lines.add(new SettlementLine("LINE_" + UUID.randomUUID(), p2.getId().toString(), p2.getAmount() + 500L, p2.getCurrency(), "SETTLED"));
            }

            if (payments.size() > 2) {
                Payment p3 = payments.get(2);
                expectedIds.add(p3.getId());
                // 3. DUPLICATE_SETTLEMENT (same payment ref twice in settlement file)
                lines.add(new SettlementLine("LINE_" + UUID.randomUUID(), p3.getId().toString(), p3.getAmount(), p3.getCurrency(), "SETTLED"));
                lines.add(new SettlementLine("LINE_" + UUID.randomUUID(), p3.getId().toString(), p3.getAmount(), p3.getCurrency(), "SETTLED"));
            }

            if (payments.size() > 3) {
                Payment p4 = payments.get(3);
                // 4. MISSING_IN_BANK (p4 is expected by ledger, but omitted from settlement lines)
                expectedIds.add(p4.getId());
            }
        }

        // 5. MISSING_IN_LEDGER (bank reported a charge with no ledger payment)
        lines.add(new SettlementLine("LINE_" + UUID.randomUUID(), "unmatched_ext_" + UUID.randomUUID(), 4999L, "USD", "SETTLED"));

        List<ReconItem> created = reconciliationService.reconcileSettlement(filename, lines, expectedIds);
        List<ReconItemResponse> responses = created.stream().map(ReconItemResponse::from).toList();
        return ResponseEntity.ok(responses);
    }
}
