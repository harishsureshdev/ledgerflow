package com.ledgerflow.processor;

import com.ledgerflow.processor.client.ProcessorChargeCommand;
import com.ledgerflow.processor.client.ProcessorChargeResult;
import com.ledgerflow.processor.client.ProcessorClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;

@Component
@Primary
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
