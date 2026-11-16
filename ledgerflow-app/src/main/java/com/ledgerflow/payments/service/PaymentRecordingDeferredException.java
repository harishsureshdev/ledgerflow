package com.ledgerflow.payments.service;

/**
 * The card was charged but the payment could not be recorded because of a temporary database
 * problem. Not final: the idempotency key is released, and a retry with the same key gets the
 * original charge replayed by the processor and records it (no second charge). Maps to 503.
 */
public class PaymentRecordingDeferredException extends RuntimeException {
    public PaymentRecordingDeferredException(String message, Throwable cause) {
        super(message, cause);
    }
}
