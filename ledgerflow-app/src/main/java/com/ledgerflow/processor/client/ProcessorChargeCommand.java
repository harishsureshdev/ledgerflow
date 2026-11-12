package com.ledgerflow.processor.client;

public record ProcessorChargeCommand(
        String token,
        Long amount,
        String currency,
        String idempotencyKey
) {
}
