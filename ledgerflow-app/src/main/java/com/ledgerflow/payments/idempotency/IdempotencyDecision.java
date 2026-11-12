package com.ledgerflow.payments.idempotency;

import java.util.UUID;

public sealed interface IdempotencyDecision {

    record Proceed(UUID idempotencyRecordId) implements IdempotencyDecision {}

    record Replay(int statusCode, String responseBody) implements IdempotencyDecision {}
}
