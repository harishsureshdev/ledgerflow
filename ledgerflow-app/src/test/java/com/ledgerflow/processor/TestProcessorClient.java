package com.ledgerflow.processor;

import com.ledgerflow.processor.client.ProcessorChargeCommand;
import com.ledgerflow.processor.client.ProcessorChargeResult;
import com.ledgerflow.processor.client.ProcessorClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

/**
 * In-memory stand-in for the card processor used by most tests. Network-fault tests turn it
 * off (ledgerflow.test.processor-stub=false) to exercise the real HTTP client through Toxiproxy.
 */
@Component
@Primary
@ConditionalOnProperty(name = "ledgerflow.test.processor-stub", havingValue = "true", matchIfMissing = true)
public class TestProcessorClient implements ProcessorClient {

    private volatile Function<ProcessorChargeCommand, ProcessorChargeResult> handler = cmd ->
            new ProcessorChargeResult("ch_test_" + UUID.randomUUID(), "APPROVED", cmd.amount(), cmd.currency(), null, Instant.now());

    @Override
    public ProcessorChargeResult authorizeAndCapture(ProcessorChargeCommand command) {
        return handler.apply(command);
    }

    public void setHandler(Function<ProcessorChargeCommand, ProcessorChargeResult> handler) {
        this.handler = handler;
    }

    public void reset() {
        this.handler = cmd ->
                new ProcessorChargeResult("ch_test_" + UUID.randomUUID(), "APPROVED", cmd.amount(), cmd.currency(), null, Instant.now());
    }
}
