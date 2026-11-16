package com.ledgerflow.processor.client;

/**
 * Refund (void) of a processor charge. Used to compensate when the processor charged the card
 * but LedgerFlow could not record the payment for a reason retrying will not fix.
 *
 * @param idempotencyKey stable per charge, so a retried refund never refunds twice
 */
public record ProcessorRefundCommand(
        String processorTransactionId,
        Long amount,
        String currency,
        String idempotencyKey
) {
}
