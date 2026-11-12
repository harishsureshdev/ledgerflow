package com.ledgerflow.payments.idempotency;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_keys", uniqueConstraints = {
        @UniqueConstraint(name = "uq_merchant_idempotency_key", columnNames = {"merchant_id", "idempotency_key"})
})
public class IdempotencyKeyRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private IdempotencyStatus status;

    @Column(name = "status_code")
    private Integer statusCode;

    @Column(name = "response_body", columnDefinition = "TEXT")
    private String responseBody;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "locked_at")
    private Instant lockedAt;

    protected IdempotencyKeyRecord() {
    }

    public IdempotencyKeyRecord(String merchantId, String idempotencyKey, String requestHash) {
        this.merchantId = merchantId;
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.status = IdempotencyStatus.IN_PROGRESS;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
        this.lockedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public Integer getStatusCode() {
        return statusCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getLockedAt() {
        return lockedAt;
    }

    public void complete(int statusCode, String responseBody) {
        this.status = IdempotencyStatus.COMPLETED;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.updatedAt = Instant.now();
    }

    public void fail(int statusCode, String responseBody) {
        this.status = IdempotencyStatus.FAILED;
        this.statusCode = statusCode;
        this.responseBody = responseBody;
        this.updatedAt = Instant.now();
    }
}
