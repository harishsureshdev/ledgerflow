package com.ledgerflow.processor.client;

public interface ProcessorClient {
    ProcessorChargeResult authorizeAndCapture(ProcessorChargeCommand command);

    /** Refunds a charge. Idempotent per {@code command.idempotencyKey()}. */
    ProcessorRefundResult refund(ProcessorRefundCommand command);
}
