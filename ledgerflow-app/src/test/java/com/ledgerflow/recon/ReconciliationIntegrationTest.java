package com.ledgerflow.recon;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.dto.PaymentResponse;
import com.ledgerflow.payments.service.PaymentService;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.service.ReconciliationService;
import com.ledgerflow.recon.service.SettlementLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Test
    @DisplayName("Reconciliation Engine: Injecting N mismatches into settlement file flags exactly N mismatches")
    void injectingNMismatchesFlagsExactlyN() {
        String merchantId = "merch_" + UUID.randomUUID();

        // 1. Seed 3 real payments in DB
        PaymentResponse p1 = paymentService.processPayment(
                merchantId, "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_" + UUID.randomUUID(), 2000L, "USD", "tok_visa_4242")
        ).response();

        PaymentResponse p2 = paymentService.processPayment(
                merchantId, "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_" + UUID.randomUUID(), 4000L, "USD", "tok_visa_4242")
        ).response();

        PaymentResponse p3 = paymentService.processPayment(
                merchantId, "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_" + UUID.randomUUID(), 6000L, "USD", "tok_visa_4242")
        ).response();

        // 2. Prepare settlement file lines with exactly 4 injected mismatches:
        // Mismatch 1: AMOUNT_MISMATCH (P1 is 2000 in DB, but 2500 in settlement file)
        SettlementLine line1 = new SettlementLine("line_1", p1.id().toString(), 2500L, "USD", "APPROVED");

        // Mismatch 2: DUPLICATE_SETTLEMENT (P2 appears twice in bank settlement file)
        SettlementLine line2A = new SettlementLine("line_2A", p2.id().toString(), 4000L, "USD", "APPROVED");
        SettlementLine line2B = new SettlementLine("line_2B", p2.id().toString(), 4000L, "USD", "APPROVED");

        // Mismatch 3: MISSING_IN_LEDGER (Bank file has a payment that does not exist in our database)
        String ghostPaymentId = UUID.randomUUID().toString();
        SettlementLine line3 = new SettlementLine("line_3", ghostPaymentId, 5000L, "USD", "APPROVED");

        // Mismatch 4: MISSING_IN_BANK (P3 exists in DB and was expected, but omitted from settlement lines)
        Set<UUID> expectedPaymentIds = Set.of(p1.id(), p2.id(), p3.id());

        List<SettlementLine> settlementLines = List.of(line1, line2A, line2B, line3);

        // 3. Run Reconciliation
        String filename = "settlement_recon_" + UUID.randomUUID() + ".csv";
        List<ReconItem> mismatches = reconciliationService.reconcileSettlement(filename, settlementLines, expectedPaymentIds);

        // Verify: Exactly 4 mismatches flagged
        assertThat(mismatches).hasSize(4);

        Set<MismatchType> observedTypes = new HashSet<>();
        for (ReconItem item : mismatches) {
            observedTypes.add(item.getMismatchType());
        }

        assertThat(observedTypes).containsExactlyInAnyOrder(
                MismatchType.AMOUNT_MISMATCH,
                MismatchType.DUPLICATE_SETTLEMENT,
                MismatchType.MISSING_IN_LEDGER,
                MismatchType.MISSING_IN_BANK
        );

        // Verify individual mismatch details
        ReconItem amountMismatch = mismatches.stream()
                .filter(m -> m.getMismatchType() == MismatchType.AMOUNT_MISMATCH).findFirst().orElseThrow();
        assertThat(amountMismatch.getPaymentReference()).isEqualTo(p1.id().toString());
        assertThat(amountMismatch.getBankAmount()).isEqualTo(2500L);
        assertThat(amountMismatch.getLedgerAmount()).isEqualTo(2000L);

        ReconItem missingInBank = mismatches.stream()
                .filter(m -> m.getMismatchType() == MismatchType.MISSING_IN_BANK).findFirst().orElseThrow();
        assertThat(missingInBank.getPaymentReference()).isEqualTo(p3.id().toString());
        assertThat(missingInBank.getLedgerAmount()).isEqualTo(6000L);
    }
}
