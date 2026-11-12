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
