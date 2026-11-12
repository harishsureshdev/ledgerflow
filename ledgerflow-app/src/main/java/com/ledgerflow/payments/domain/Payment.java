package com.ledgerflow.payments.domain;

import com.ledgerflow.common.domain.Money;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "merchant_id", nullable = false, length = 64)
    private String merchantId;

    @Column(name = "customer_id", nullable = false, length = 64)
    private String customerId;

    @Column(name = "amount", nullable = false)
    private Long amount;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "idempotency_key_id")
    private UUID idempotencyKeyId;

    @Column(name = "payment_token", nullable = false, length = 128)
    private String paymentToken;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    protected Payment() {
    }

    public Payment(String merchantId, String customerId, Money money, String paymentToken, UUID idempotencyKeyId) {
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId must not be null");
        this.customerId = Objects.requireNonNull(customerId, "customerId must not be null");
        Objects.requireNonNull(money, "money must not be null");
        this.amount = money.amount();
        this.currency = money.currency();
        this.paymentToken = validateToken(paymentToken);
        this.idempotencyKeyId = idempotencyKeyId;
        this.status = PaymentStatus.INITIATED;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    private static String validateToken(String token) {
        Objects.requireNonNull(token, "Payment token must not be null");
        if (!token.startsWith("tok_")) {
            throw new IllegalArgumentException("PCI compliance violation: only tokenized payment methods ('tok_...') are permitted");
        }
        return token;
    }

    public UUID getId() {
        return id;
    }

    public String getMerchantId() {
        return merchantId;
    }

    public String getCustomerId() {
        return customerId;
    }

    public Long getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Money getMoney() {
        return Money.of(amount, currency);
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public UUID getIdempotencyKeyId() {
        return idempotencyKeyId;
    }

    public String getPaymentToken() {
        return paymentToken;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void transitionTo(PaymentStatus newStatus) {
        PaymentStateMachine.validateTransition(this.status, newStatus);
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void markFailed(String errorCode, String errorMessage) {
        transitionTo(PaymentStatus.FAILED);
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
    }
}
