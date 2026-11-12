package com.ledgerflow.processor.model;

import java.time.Instant;

public record ChargeResponse(
        String processorTransactionId,
        String status, // APPROVED, DECLINED, FAILED
        Long amount,
        String currency,
        String failureReason,
        Instant processedAt
) {
    public static ChargeResponse approved(String id, Long amount, String currency) {
        return new ChargeResponse(id, "APPROVED", amount, currency, null, Instant.now());
    }

    public static ChargeResponse declined(String id, Long amount, String currency, String reason) {
        return new ChargeResponse(id, "DECLINED", amount, currency, reason, Instant.now());
    }
}
