package com.ledgerflow.processor.service;

import com.ledgerflow.processor.model.ChaosConfig;
import com.ledgerflow.processor.model.ChargeRequest;
import com.ledgerflow.processor.model.ChargeResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChaosSimulationServiceTest {

    private ChaosSimulationService service;

    @BeforeEach
    void setUp() {
        service = new ChaosSimulationService();
    }

    @Test
    @DisplayName("Refunds: a known charge is refunded once per refund key; unknown charges and wrong amounts fail")
    void refundsAreIdempotentAndValidated() {
        var charge = service.processCharge(new ChargeRequest("tok_visa", 6000L, "USD", "key_" + UUID.randomUUID()));
        String refundKey = "refund_" + UUID.randomUUID();

        var first = service.processRefund(new com.ledgerflow.processor.model.RefundRequest(
                charge.processorTransactionId(), 6000L, "USD", refundKey));
        var again = service.processRefund(new com.ledgerflow.processor.model.RefundRequest(
                charge.processorTransactionId(), 6000L, "USD", refundKey));
        assertThat(first.status()).isEqualTo("REFUNDED");
        assertThat(again.refundId()).as("same refund key replays, no second refund").isEqualTo(first.refundId());

        assertThat(service.processRefund(new com.ledgerflow.processor.model.RefundRequest(
                "ch_unknown", 6000L, "USD", "refund_" + UUID.randomUUID())).status()).isEqualTo("FAILED");
        assertThat(service.processRefund(new com.ledgerflow.processor.model.RefundRequest(
                charge.processorTransactionId(), 9999L, "USD", "refund_" + UUID.randomUUID())).status()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Normal operation approves valid charge and replays for identical key")
    void normalApprovalAndReplay() {
        String key = "key_" + UUID.randomUUID();
        ChargeRequest request = new ChargeRequest("tok_visa", 5000L, "USD", key);

        ChargeResponse response = service.processCharge(request);
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.amount()).isEqualTo(5000L);

        // Replay
        ChargeResponse replayed = service.processCharge(request);
        assertThat(replayed.processorTransactionId()).isEqualTo(response.processorTransactionId());
    }

    @Test
    @DisplayName("Simulates card decline when using tok_decline")
    void declinesSpecialToken() {
        ChargeRequest request = new ChargeRequest("tok_decline", 2500L, "USD", "key_" + UUID.randomUUID());
        ChargeResponse response = service.processCharge(request);

        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.failureReason()).contains("INSUFFICIENT_FUNDS");
    }

    @Test
    @DisplayName("Injected 100% failure rate triggers ProcessorDownException")
    void failureRateChaos() {
        service.setChaosConfig(new ChaosConfig(0, 1.0, 0.0));

        ChargeRequest request = new ChargeRequest("tok_visa", 1000L, "USD", "key_" + UUID.randomUUID());
        assertThatThrownBy(() -> service.processCharge(request))
                .isInstanceOf(ChaosSimulationService.ProcessorDownException.class);
    }
}
