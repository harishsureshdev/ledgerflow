package com.ledgerflow.payments.idempotency;

import java.util.UUID;

public sealed interface IdempotencyDecision {

    /**
     * @param tookOverExpiredLease true when this request took over a claim abandoned by a crashed
     *                             or hung earlier attempt, which may already have saved a payment
     */
    record Proceed(UUID idempotencyRecordId, boolean tookOverExpiredLease) implements IdempotencyDecision {}

    record Replay(int statusCode, String responseBody) implements IdempotencyDecision {}
}
