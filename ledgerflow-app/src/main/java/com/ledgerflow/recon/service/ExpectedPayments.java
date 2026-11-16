package com.ledgerflow.recon.service;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Which ledger payments the bank was expected to settle; any of them absent from the file
 * is reported as MISSING_IN_BANK.
 *
 * <p>Either an explicit set of payment ids (demo / ad-hoc runs), or every CAPTURED/SETTLED
 * payment created in a time window (the nightly job: the previous UTC business day).
 */
public record ExpectedPayments(Set<UUID> paymentIds, Instant windowStart, Instant windowEnd) {

    public static ExpectedPayments ids(Set<UUID> ids) {
        return new ExpectedPayments(ids == null ? Set.of() : Set.copyOf(ids), null, null);
    }

    public static ExpectedPayments window(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("window start must be before end");
        }
        return new ExpectedPayments(null, start, end);
    }

    public static ExpectedPayments none() {
        return new ExpectedPayments(Set.of(), null, null);
    }

    public boolean isWindow() {
        return windowStart != null;
    }
}
