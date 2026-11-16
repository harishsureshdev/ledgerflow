package com.ledgerflow.processor.service;

import com.ledgerflow.processor.model.ChaosConfig;
import com.ledgerflow.processor.model.ChargeRequest;
import com.ledgerflow.processor.model.ChargeResponse;
import com.ledgerflow.processor.model.RefundRequest;
import com.ledgerflow.processor.model.RefundResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ChaosSimulationService {

    private static final Logger log = LoggerFactory.getLogger(ChaosSimulationService.class);

    private volatile ChaosConfig chaosConfig = ChaosConfig.normal();
    private final Map<String, ChargeResponse> idempotencyStore = new ConcurrentHashMap<>();
    private final Map<String, RefundResponse> refundStore = new ConcurrentHashMap<>();

    public void setChaosConfig(ChaosConfig config) {
        this.chaosConfig = config != null ? config : ChaosConfig.normal();
        log.info("Updated sim-processor chaos config: {}", this.chaosConfig);
    }

    public ChaosConfig getChaosConfig() {
        return chaosConfig;
    }

    public ChargeResponse processCharge(ChargeRequest request) {
        // 1. Idempotency check at processor level
        if (idempotencyStore.containsKey(request.idempotencyKey())) {
            log.info("sim-processor replaying response for idempotencyKey: {}", request.idempotencyKey());
            return idempotencyStore.get(request.idempotencyKey());
        }

        // 2. Injected Latency Chaos
        if (chaosConfig.latencyMs() > 0) {
            try {
                log.info("Simulating network latency: {} ms", chaosConfig.latencyMs());
                Thread.sleep(chaosConfig.latencyMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during simulated latency", e);
            }
        }

        // 3. Injected Failure Chaos
        if (chaosConfig.failureRate() > 0.0) {
            double roll = ThreadLocalRandom.current().nextDouble();
            if (roll < chaosConfig.failureRate()) {
                log.warn("Chaos triggered simulated processor failure! roll={} < failureRate={}", roll, chaosConfig.failureRate());
                throw new ProcessorDownException("Simulated 500: Card network gateway timeout or connection failure");
            }
        }

        // 4. Token-based testing triggers (e.g. tok_decline)
        if ("tok_decline".equalsIgnoreCase(request.token())) {
            ChargeResponse declined = ChargeResponse.declined(
                    "ch_sim_" + UUID.randomUUID(), request.amount(), request.currency(), "CARD_DECLINED_INSUFFICIENT_FUNDS");
            idempotencyStore.put(request.idempotencyKey(), declined);
            return declined;
        }

        // 5. Approved Charge
        String txId = "ch_sim_" + UUID.randomUUID();
        ChargeResponse approved = ChargeResponse.approved(txId, request.amount(), request.currency());
        idempotencyStore.put(request.idempotencyKey(), approved);
        return approved;
    }

    /**
     * Refunds a charge this processor made. Idempotent per refund key: a repeat returns the
     * original refund. Refunding an unknown charge, or with a different amount, fails.
     */
    public RefundResponse processRefund(RefundRequest request) {
        RefundResponse existing = refundStore.get(request.idempotencyKey());
        if (existing != null) {
            return existing;
        }
        boolean knownCharge = idempotencyStore.values().stream().anyMatch(c ->
                request.processorTransactionId().equals(c.processorTransactionId())
                        && "APPROVED".equals(c.status())
                        && request.amount().equals(c.amount()));
        RefundResponse response = knownCharge
                ? new RefundResponse("re_sim_" + UUID.randomUUID(), request.processorTransactionId(), "REFUNDED")
                : new RefundResponse(null, request.processorTransactionId(), "FAILED");
        if (knownCharge) {
            refundStore.put(request.idempotencyKey(), response);
            log.info("Refunded charge {} ({} {})", request.processorTransactionId(), request.amount(), request.currency());
        }
        return response;
    }

    public static class ProcessorDownException extends RuntimeException {
        public ProcessorDownException(String message) {
            super(message);
        }
    }
}
