package com.ledgerflow.payments.domain;

/**
 * Enforces the payment state machine invariants.
 *
 * State Transitions:
 *   INITIATED -> AUTHORIZED, FAILED
 *   AUTHORIZED -> CAPTURED, FAILED
 *   CAPTURED -> SETTLED, REVERSED
 *   SETTLED -> REVERSED
 *
 * All other transitions are rejected at the domain layer.
 */
public final class PaymentStateMachine {

    private PaymentStateMachine() {
        // Utility class
    }

    public static void validateTransition(PaymentStatus current, PaymentStatus next) {
        if (current == null) {
            throw new IllegalArgumentException("Current state must not be null");
        }
        if (next == null) {
            throw new IllegalArgumentException("Target state must not be null");
        }
        if (!current.canTransitionTo(next)) {
            throw new IllegalStateException(
                    String.format("Illegal payment state transition from %s to %s. Allowed transitions: %s",
                            current, next, current.getAllowedNextStates())
            );
        }
    }

    public static boolean isValidTransition(PaymentStatus current, PaymentStatus next) {
        return current != null && current.canTransitionTo(next);
    }
}
