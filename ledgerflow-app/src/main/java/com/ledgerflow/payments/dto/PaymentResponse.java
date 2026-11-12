package com.ledgerflow.payments.dto;

import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.domain.PaymentStatus;

import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        String merchantId,
        String customerId,
        Long amount,
        String currency,
        PaymentStatus status,
        String paymentToken,
        Instant createdAt
) {
    public static PaymentResponse from(Payment payment) {
        return new PaymentResponse(
                payment.getId(),
                payment.getMerchantId(),
                payment.getCustomerId(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getStatus(),
                payment.getPaymentToken(),
                payment.getCreatedAt()
        );
    }
}
