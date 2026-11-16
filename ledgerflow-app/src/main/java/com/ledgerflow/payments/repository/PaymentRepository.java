package com.ledgerflow.payments.repository;

import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    List<Payment> findByMerchantId(String merchantId);

    List<Payment> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<Payment> findAllByOrderByCreatedAtDesc();

    List<Payment> findByStatus(PaymentStatus status);

    /** The payment created under an idempotency claim, if any (at most one: unique index, V8). */
    java.util.Optional<Payment> findByIdempotencyKeyId(UUID idempotencyKeyId);
}
