package com.ledgerflow.processor;

import com.ledgerflow.processor.client.ProcessorChargeCommand;
import com.ledgerflow.processor.client.ProcessorChargeResult;
import com.ledgerflow.processor.client.ProcessorClient;
import com.ledgerflow.processor.client.ProcessorRefundCommand;
import com.ledgerflow.processor.client.ProcessorRefundResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * In-memory stand-in for the card processor used by most tests. Network-fault tests turn it
 * off (ledgerflow.test.processor-stub=false) to exercise the real HTTP client through Toxiproxy.
 *
 * <p>Like a real processor, approved charges are idempotent per key (a repeat returns the
 * original charge), and every charge and refund is recorded so tests can assert on them.
 */
@Component
@Primary
@ConditionalOnProperty(name = "ledgerflow.test.processor-stub", havingValue = "true", matchIfMissing = true)
public class TestProcessorClient implements ProcessorClient {

    private final Map<String, ProcessorChargeResult> chargesByKey = new ConcurrentHashMap<>();
    private final List<ProcessorRefundCommand> refunds = new CopyOnWriteArrayList<>();
    private volatile Function<ProcessorChargeCommand, ProcessorChargeResult> handler = TestProcessorClient::approve;

    private static ProcessorChargeResult approve(ProcessorChargeCommand cmd) {
        return new ProcessorChargeResult("ch_test_" + UUID.randomUUID(), "APPROVED", cmd.amount(), cmd.currency(), null, Instant.now());
    }

    @Override
    public ProcessorChargeResult authorizeAndCapture(ProcessorChargeCommand command) {
        ProcessorChargeResult existing = chargesByKey.get(command.idempotencyKey());
        if (existing != null) {
            return existing; // processor-side idempotency: replay, no second charge
        }
        ProcessorChargeResult result = handler.apply(command);
        if (result.isApproved()) {
            chargesByKey.put(command.idempotencyKey(), result);
        }
        return result;
    }

    @Override
    public ProcessorRefundResult refund(ProcessorRefundCommand command) {
        boolean alreadyRefunded = refunds.stream().anyMatch(r -> r.idempotencyKey().equals(command.idempotencyKey()));
        if (!alreadyRefunded) {
            refunds.add(command);
        }
        return new ProcessorRefundResult("re_test_" + command.idempotencyKey(), command.processorTransactionId(), "REFUNDED");
    }

    public void setHandler(Function<ProcessorChargeCommand, ProcessorChargeResult> handler) {
        this.handler = handler;
    }

    /** Distinct approved charges (repeats with a known key are not new charges). */
    public int chargesMade() {
        return chargesByKey.size();
    }

    public List<ProcessorRefundCommand> refunds() {
        return List.copyOf(refunds);
    }

    public void reset() {
        this.handler = TestProcessorClient::approve;
        chargesByKey.clear();
        refunds.clear();
    }
}
