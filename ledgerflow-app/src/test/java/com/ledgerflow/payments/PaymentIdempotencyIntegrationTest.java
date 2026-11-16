package com.ledgerflow.payments;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.ledger.service.LedgerService;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.dto.PaymentResponse;
import com.ledgerflow.payments.idempotency.IdempotencyConflictException;
import com.ledgerflow.payments.idempotency.IdempotencyPayloadMismatchException;
import com.ledgerflow.payments.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentIdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AccountProvisioningService accountProvisioningService;

    @Autowired
    private LedgerService ledgerService;

    @AfterEach
    void verifyInvariant() {
        ledgerService.assertJournalSumsToZero();
    }

    @Test
    @DisplayName("Same key with identical payload replays the cached 201 response")
    void sameKeySamePayloadReplaysResponse() {
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();

        accountProvisioningService.getOrCreateCustomerAccount(customerId, "USD", 50000L);

        CreatePaymentRequest request = new CreatePaymentRequest(
                customerId,
                1500L,
                "USD",
                "tok_visa_4242"
        );

        // First call
        PaymentService.PaymentResult firstResult = paymentService.processPayment(merchantId, idempotencyKey, request);
        assertThat(firstResult.replayed()).isFalse();
        assertThat(firstResult.statusCode()).isEqualTo(201);
        UUID firstPaymentId = firstResult.response().id();

        // Second call (same key, same payload)
        PaymentService.PaymentResult secondResult = paymentService.processPayment(merchantId, idempotencyKey, request);
        assertThat(secondResult.replayed()).isTrue();
        assertThat(secondResult.statusCode()).isEqualTo(201);
        assertThat(secondResult.response().id()).isEqualTo(firstPaymentId);
        assertThat(secondResult.response().amount()).isEqualTo(1500L);
    }

    @Test
    @DisplayName("Same key with different payload throws 422 Unprocessable Content")
    void sameKeyDifferentPayloadThrows422() {
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();

        accountProvisioningService.getOrCreateCustomerAccount(customerId, "USD", 50000L);

        CreatePaymentRequest initialRequest = new CreatePaymentRequest(
                customerId,
                2000L,
                "USD",
                "tok_visa_4242"
        );

        // First call succeeds
        PaymentService.PaymentResult result = paymentService.processPayment(merchantId, idempotencyKey, initialRequest);
        assertThat(result.statusCode()).isEqualTo(201);

        // Second call with different amount
        CreatePaymentRequest alteredRequest = new CreatePaymentRequest(
                customerId,
                3000L, // changed amount
                "USD",
                "tok_visa_4242"
        );

        assertThatThrownBy(() -> paymentService.processPayment(merchantId, idempotencyKey, alteredRequest))
                .isInstanceOf(IdempotencyPayloadMismatchException.class)
                .hasMessageContaining("was already used with a different request payload");
    }

    @Test
    @DisplayName("Enforces PCI-awareness: non-tokenized payment method rejected")
    void nonTokenPaymentMethodRejected() {
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();

        CreatePaymentRequest cardRequest = new CreatePaymentRequest(
                customerId,
                1000L,
                "USD",
                "4111111111111111" // Raw PAN card number
        );

        assertThatThrownBy(() -> paymentService.processPayment(merchantId, idempotencyKey, cardRequest))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PCI compliance violation");
        // Bug: the raw card number used to reach the card processor before being rejected
        assertThat(testProcessorClient.chargesMade()).as("a raw PAN must never be sent to the processor").isZero();
    }
}
