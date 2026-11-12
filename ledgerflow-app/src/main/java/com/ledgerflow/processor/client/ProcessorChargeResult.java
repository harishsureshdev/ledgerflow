package com.ledgerflow.processor.client;

import java.time.Instant;

public record ProcessorChargeResult(
        String processorTransactionId,
        String status, // APPROVED, DECLINED, FAILED
        Long amount,
        String currency,
        String failureReason,
        Instant processedAt
) {
    public boolean isApproved() {
        return "APPROVED".equalsIgnoreCase(status);
    }
}
