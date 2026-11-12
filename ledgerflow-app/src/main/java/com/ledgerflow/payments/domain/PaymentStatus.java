package com.ledgerflow.payments.domain;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Domain enumeration of valid payment states.
 */
public enum PaymentStatus {
    INITIATED,
    AUTHORIZED,
    CAPTURED,
    SETTLED,
    FAILED,
    REVERSED;

    private Set<PaymentStatus> allowedNextStates;

    static {
        INITIATED.allowedNextStates = Collections.unmodifiableSet(EnumSet.of(AUTHORIZED, FAILED));
        AUTHORIZED.allowedNextStates = Collections.unmodifiableSet(EnumSet.of(CAPTURED, FAILED));
        CAPTURED.allowedNextStates = Collections.unmodifiableSet(EnumSet.of(SETTLED, REVERSED));
        SETTLED.allowedNextStates = Collections.unmodifiableSet(EnumSet.of(REVERSED));
        FAILED.allowedNextStates = Collections.emptySet();
        REVERSED.allowedNextStates = Collections.emptySet();
    }

    public boolean canTransitionTo(PaymentStatus target) {
        if (target == null) {
            return false;
        }
        return allowedNextStates.contains(target);
    }

    public Set<PaymentStatus> getAllowedNextStates() {
        return allowedNextStates;
    }
}
