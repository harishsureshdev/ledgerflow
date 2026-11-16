package com.ledgerflow.payments.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository repository;

    public IdempotencyService(IdempotencyKeyRepository repository) {
        this.repository = repository;
    }

    /**
     * Attempts to acquire an idempotency lock for the given merchant and key.
     *
     * Backed by PostgreSQL unique constraint: UNIQUE (merchant_id, idempotency_key)
     * and ON CONFLICT DO NOTHING for non-blocking atomic acquisition.
     *
     * Invariants:
     * - Same key + same hash while IN_PROGRESS -> 409 Conflict
     * - Same key + different hash -> 422 Unprocessable Content
     * - Same key + same hash when COMPLETED/FAILED -> return stored response
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public IdempotencyDecision acquireOrCheck(String merchantId, String key, String requestPayload) {
        String requestHash = RequestHasher.computeHash(requestPayload);

        int inserted = repository.insertIfNotExists(merchantId, key, requestHash);
        if (inserted == 1) {
            IdempotencyKeyRecord record = repository.findByMerchantIdAndIdempotencyKey(merchantId, key)
                    .orElseThrow(() -> new IllegalStateException("Failed to retrieve just-inserted idempotency record"));
            return new IdempotencyDecision.Proceed(record.getId());
        }

        // Row already existed: fetch and evaluate
        IdempotencyKeyRecord existing = repository.findByMerchantIdAndIdempotencyKey(merchantId, key)
                .orElseThrow(() -> new IllegalStateException("Idempotency record not found after conflict"));

        return evaluateExisting(existing, requestHash);
    }

    private IdempotencyDecision evaluateExisting(IdempotencyKeyRecord record, String currentHash) {
        if (!record.getRequestHash().equals(currentHash)) {
            throw new IdempotencyPayloadMismatchException(
                    "Idempotency-Key '" + record.getIdempotencyKey() + "' was already used with a different request payload."
            );
        }

        if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
            throw new IdempotencyConflictException(
                    "A request with Idempotency-Key '" + record.getIdempotencyKey() + "' is currently in progress."
            );
        }

        return new IdempotencyDecision.Replay(record.getStatusCode(), record.getResponseBody());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(UUID recordId, int statusCode, String responseBody) {
        repository.findById(recordId).ifPresent(record -> {
            record.complete(statusCode, responseBody);
            repository.saveAndFlush(record);
        });
    }

    /**
     * Gives up an IN_PROGRESS claim without storing a response, so the same key can be retried.
     * Used only when the outcome is not final (processor unavailable / unknown). Completed or
     * failed records are never released: their stored response is the answer forever.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(UUID recordId) {
        repository.findById(recordId)
                .filter(r -> r.getStatus() == IdempotencyStatus.IN_PROGRESS)
                .ifPresent(r -> {
                    repository.delete(r);
                    repository.flush();
                });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID recordId, int statusCode, String responseBody) {
        repository.findById(recordId).ifPresent(record -> {
            record.fail(statusCode, responseBody);
            repository.saveAndFlush(record);
        });
    }
}
