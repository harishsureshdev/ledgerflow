package com.ledgerflow.processor.client;

public interface ProcessorClient {
    ProcessorChargeResult authorizeAndCapture(ProcessorChargeCommand command);
}
