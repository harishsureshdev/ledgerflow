package com.ledgerflow.processor.client;

/**
 * The card processor could not give a definite answer (unreachable, timed out, 5xx, or the
 * circuit breaker is open). Not a decline: the client should retry with the SAME Idempotency-Key.
 *
 * <p>{@code outcomeUnknown} is true when the request may have reached the processor (read timeout,
 * 5xx), so the card might already be charged. That is safe because retries reuse the same
 * processor-side idempotency key, so the processor replays the original charge instead of
 * charging again. Nightly reconciliation is the backstop for anything still unresolved.
 */
public class ProcessorUnavailableException extends RuntimeException {

    private final boolean outcomeUnknown;

    public ProcessorUnavailableException(String message, boolean outcomeUnknown, Throwable cause) {
        super(message, cause);
        this.outcomeUnknown = outcomeUnknown;
    }

    public boolean isOutcomeUnknown() {
        return outcomeUnknown;
    }
}
