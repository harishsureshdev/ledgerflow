package com.ledgerflow.processor.model;

public record RefundResponse(
        String refundId,
        String processorTransactionId,
        String status // REFUNDED, FAILED
) {
}
