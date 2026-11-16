package com.ledgerflow.payments.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKeyRecord, UUID> {

    Optional<IdempotencyKeyRecord> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

    /**
     * Atomically inserts a new idempotency key record if one does not already exist.
     * Uses PostgreSQL ON CONFLICT DO NOTHING to guarantee concurrency safety without
     * aborting transactions or triggering 25P02 errors.
     */
    @Modifying
    @Query(value = """
        INSERT INTO idempotency_keys (id, merchant_id, idempotency_key, request_hash, status, created_at, updated_at, locked_at)
        VALUES (gen_random_uuid(), :merchantId, :key, :requestHash, 'IN_PROGRESS', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        ON CONFLICT (merchant_id, idempotency_key) DO NOTHING
        """, nativeQuery = true)
    int insertIfNotExists(@Param("merchantId") String merchantId,
                          @Param("key") String key,
                          @Param("requestHash") String requestHash);

    /**
     * Takes over an IN_PROGRESS claim whose lease has expired (the holder crashed or hung).
     * Atomic: of several concurrent retries exactly one gets 1 row; the rest get 0 (-> 409).
     * Uses the database clock for both writing and comparing locked_at.
     */
    @Modifying
    @Query(value = """
        UPDATE idempotency_keys
        SET locked_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP
        WHERE id = :id AND status = 'IN_PROGRESS'
          AND (locked_at IS NULL OR locked_at < CURRENT_TIMESTAMP - make_interval(secs => :leaseSeconds))
        """, nativeQuery = true)
    int takeOverExpiredLease(@Param("id") java.util.UUID id, @Param("leaseSeconds") double leaseSeconds);
}
