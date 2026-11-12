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
}
