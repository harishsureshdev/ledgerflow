package com.ledgerflow.processor.client;

public record ProcessorRefundResult(
        String refundId,
        String processorTransactionId,
        String status // REFUNDED, FAILED
) {
    public boolean isRefunded() {
        return "REFUNDED".equalsIgnoreCase(status);
    }
}
