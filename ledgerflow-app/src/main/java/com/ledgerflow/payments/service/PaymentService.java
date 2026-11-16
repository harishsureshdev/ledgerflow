package com.ledgerflow.payments.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.common.domain.Money;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.domain.TransactionType;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.ledger.service.LedgerService;
import com.ledgerflow.ledger.service.PostTransactionCommand;
import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.domain.PaymentStatus;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.dto.PaymentResponse;
import com.ledgerflow.payments.idempotency.IdempotencyDecision;
import com.ledgerflow.payments.idempotency.IdempotencyService;
import com.ledgerflow.payments.outbox.OutboxService;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.processor.client.ProcessorChargeCommand;
import com.ledgerflow.processor.client.ProcessorChargeResult;
import com.ledgerflow.processor.client.ProcessorClient;
import com.ledgerflow.processor.client.ProcessorUnavailableException;
import com.ledgerflow.processor.client.ProcessorRefundCommand;
import com.ledgerflow.processor.client.ProcessorRefundResult;
import com.ledgerflow.ledger.exception.InsufficientFundsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final LedgerService ledgerService;
    private final AccountProvisioningService accountProvisioningService;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final ProcessorClient processorClient;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;
    private final MeterRegistry meters;

    static String processorKey(String merchantId, String idempotencyKey) {
        return merchantId + ":" + idempotencyKey;
    }

    /** ledgerflow.payments{outcome=captured|declined|replayed|failed|processor_*}: the payment funnel in Grafana. */
    private void countOutcome(String outcome) {
        meters.counter("ledgerflow.payments", "outcome", outcome).increment();
    }

    public PaymentService(
            PaymentRepository paymentRepository,
            LedgerService ledgerService,
            AccountProvisioningService accountProvisioningService,
            IdempotencyService idempotencyService,
            OutboxService outboxService,
            ProcessorClient processorClient,
            ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager,
            MeterRegistry meters
    ) {
        this.meters = meters;
        this.paymentRepository = paymentRepository;
        this.ledgerService = ledgerService;
        this.accountProvisioningService = accountProvisioningService;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.processorClient = processorClient;
        this.objectMapper = objectMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /**
     * Executes payment processing with strict idempotency and double-entry ledger guarantee.
     *
     * Core Architectural Rule:
     * Card network / processor calls are executed OUTSIDE of any database transaction.
     * We never hold database row locks or connections across an external network hop.
     */
    public PaymentResult processPayment(String merchantId, String idempotencyKey, CreatePaymentRequest request) {
        // PCI: reject anything that isn't a token BEFORE it is stored, hashed, logged or sent to the
        // processor. (The Payment entity re-checks, but that runs after the processor call.)
        if (request.paymentToken() == null || !request.paymentToken().startsWith("tok_")) {
            throw new IllegalArgumentException("PCI compliance violation: only tokenized payment methods ('tok_...') are permitted");
        }

        String payloadJson = serializePayload(request);

        IdempotencyDecision decision = idempotencyService.acquireOrCheck(merchantId, idempotencyKey, payloadJson);

        if (decision instanceof IdempotencyDecision.Replay replay) {
            PaymentResponse replayedResponse = deserializeResponse(replay.responseBody());
            countOutcome("replayed");
            return new PaymentResult(replay.statusCode(), replayedResponse, true);
        }

        IdempotencyDecision.Proceed proceed = (IdempotencyDecision.Proceed) decision;
        UUID idempotencyRecordId = proceed.idempotencyRecordId();

        // Took over an expired claim: the crashed attempt may have committed the payment before it
        // could store the response. If so, finish its bookkeeping and return that payment.
        if (proceed.tookOverExpiredLease()) {
            var recovered = completeFromExistingPayment(idempotencyRecordId);
            if (recovered != null) {
                countOutcome("recovered");
                return recovered;
            }
        }

        // 0. Cheap pre-check: don't charge a card for a payment the ledger will refuse anyway.
        //    (Advisory only; the binding check is the locked one inside the ledger transaction.)
        var knownAccount = accountProvisioningService.findCustomerAccount(request.customerId(), request.currency());
        if (knownAccount.isPresent() && !knownAccount.get().isAllowNegative()
                && knownAccount.get().getBalance() < request.amount()) {
            idempotencyService.markFailed(idempotencyRecordId, 402, serializePayload(
                    new PaymentErrorResponse("INSUFFICIENT_FUNDS", "Insufficient funds; card not charged")));
            countOutcome("declined_insufficient_funds");
            throw new PaymentDeclinedException("Insufficient funds; card not charged");
        }

        // 1. External processor call: OUTSIDE DB transaction (protected by Resilience4j).
        // The processor-side key is scoped by merchant (our own keys are unique per merchant, not
        // globally) and stays identical across retries, so the processor never charges twice.
        ProcessorChargeResult chargeResult;
        try {
            chargeResult = processorClient.authorizeAndCapture(new ProcessorChargeCommand(
                    request.paymentToken(), request.amount(), request.currency(), processorKey(merchantId, idempotencyKey)));
        } catch (ProcessorUnavailableException e) {
            // No definite answer (maybe charged, maybe not). Not a decline and not final: release the
            // key so the client's retry with the same Idempotency-Key runs again; the processor will
            // replay the original charge if it happened. Reconciliation is the backstop.
            idempotencyService.release(idempotencyRecordId);
            countOutcome(e.isOutcomeUnknown() ? "processor_outcome_unknown" : "processor_unavailable");
            throw e;
        }

        if (!chargeResult.isApproved()) {
            String errorJson = serializePayload(new PaymentErrorResponse(
                    "PROCESSOR_DECLINED",
                    chargeResult.failureReason() != null ? chargeResult.failureReason() : "Payment charge not approved by card network"
            ));
            idempotencyService.markFailed(idempotencyRecordId, 402, errorJson);
            countOutcome("declined");
            throw new PaymentDeclinedException("Card processor declined: " + chargeResult.failureReason());
        }

        // 2. Short Database Transaction: commit payment, double-entry ledger, and outbox event together
        try {
            PaymentResponse response = transactionTemplate.execute(status ->
                    executePaymentTransaction(merchantId, idempotencyRecordId, request, chargeResult.processorTransactionId())
            );
            String responseJson = serializePayload(response);
            idempotencyService.markCompleted(idempotencyRecordId, 201, responseJson);
            countOutcome("captured");
            return new PaymentResult(201, response, false);
        } catch (Exception e) {
            // Lost a race with another attempt holding the same claim (unique index on
            // payments.idempotency_key_id): that attempt's payment is the answer, not an error.
            var winner = completeFromExistingPayment(idempotencyRecordId);
            if (winner != null) {
                countOutcome("recovered");
                return winner;
            }
            // The card IS charged at this point, but we could not record the payment.
            if (isTransient(e)) {
                // Temporary DB problem: let the client retry with the same key. The processor will
                // replay the original charge (same processor key) and we try to record it again.
                idempotencyService.release(idempotencyRecordId);
                countOutcome("record_deferred");
                throw new PaymentRecordingDeferredException(
                        "Card charged but the payment could not be recorded yet; retry with the same Idempotency-Key", e);
            }
            // Permanent failure: retrying will not help, so undo the charge.
            return compensateByRefund(idempotencyRecordId, merchantId, idempotencyKey, request, chargeResult, e);
        }
    }

    /** Refunds a charge we could not record, and stores a final failure for the idempotency key. */
    private PaymentResult compensateByRefund(UUID idempotencyRecordId, String merchantId, String idempotencyKey,
                                             CreatePaymentRequest request, ProcessorChargeResult charge, Exception cause) {
        boolean insufficientFunds = hasCause(cause, InsufficientFundsException.class);
        ProcessorRefundResult refund = processorClient.refund(new ProcessorRefundCommand(
                charge.processorTransactionId(), request.amount(), request.currency(),
                "refund:" + processorKey(merchantId, idempotencyKey)));

        if (!refund.isRefunded()) {
            // Worst case: charged, not recorded, refund failed. Nightly reconciliation flags it as
            // MISSING_IN_LEDGER; this log line and metric are the immediate alarm.
            log.error("MANUAL ACTION REQUIRED: processor charge {} for {} {} could not be recorded or refunded",
                    charge.processorTransactionId(), request.amount(), request.currency(), cause);
            countOutcome("compensation_failed");
            idempotencyService.markFailed(idempotencyRecordId, 500, serializePayload(new PaymentErrorResponse(
                    "RECORDING_FAILED", "Payment could not be recorded; refund pending manual review")));
            throw new IllegalStateException("Payment could not be recorded and the refund failed", cause);
        }

        countOutcome("compensated");
        if (insufficientFunds) {
            idempotencyService.markFailed(idempotencyRecordId, 402, serializePayload(new PaymentErrorResponse(
                    "INSUFFICIENT_FUNDS", "Insufficient funds; the card charge was refunded")));
            throw new PaymentDeclinedException("Insufficient funds; the card charge was refunded");
        }
        idempotencyService.markFailed(idempotencyRecordId, 500, serializePayload(new PaymentErrorResponse(
                "RECORDING_FAILED", "Payment could not be recorded; the card charge was refunded")));
        throw new IllegalStateException("Payment could not be recorded; the card charge was refunded", cause);
    }

    /** Errors worth retrying: lock/serialization conflicts, lost connections, an unknown commit outcome. */
    static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.dao.TransientDataAccessException
                    || t instanceof org.springframework.dao.RecoverableDataAccessException
                    || t instanceof org.springframework.transaction.CannotCreateTransactionException
                    || t instanceof org.springframework.dao.DataAccessResourceFailureException) {
                return true;
            }
            if (t instanceof java.sql.SQLException sql && sql.getSQLState() != null
                    && (sql.getSQLState().startsWith("40") || sql.getSQLState().startsWith("08"))) {
                return true; // 40xxx: serialization failure / deadlock; 08xxx: connection exception
            }
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** If a payment already exists for this claim, store it as the claim's response and return it. */
    private PaymentResult completeFromExistingPayment(UUID idempotencyRecordId) {
        return paymentRepository.findByIdempotencyKeyId(idempotencyRecordId)
                .map(existing -> {
                    PaymentResponse response = PaymentResponse.from(existing);
                    idempotencyService.markCompleted(idempotencyRecordId, 201, serializePayload(response));
                    return new PaymentResult(201, response, true);
                })
                .orElse(null);
    }

    public PaymentResponse executePaymentTransaction(String merchantId, UUID idempotencyRecordId, CreatePaymentRequest request, String processorTxId) {
        Money money = Money.of(request.amount(), request.currency());

        Account customerAccount = accountProvisioningService.getOrCreateCustomerAccount(
                request.customerId(), request.currency(), 1_000_000L);
        Account merchantAccount = accountProvisioningService.getOrCreateMerchantAccount(
                merchantId, request.currency());

        // 1. Create Payment entity in INITIATED state
        Payment payment = new Payment(
                merchantId,
                request.customerId(),
                money,
                request.paymentToken(),
                idempotencyRecordId
        );
        payment = paymentRepository.save(payment);

        // 2. Transitions: INITIATED -> AUTHORIZED -> CAPTURED
        payment.transitionTo(PaymentStatus.AUTHORIZED);
        payment.transitionTo(PaymentStatus.CAPTURED);
        paymentRepository.save(payment);

        // 3. Double-entry ledger movement (sum = 0, row locking enforced)
        String txRef = "PAYMENT-" + payment.getId();
        PostTransactionCommand command = new PostTransactionCommand(
                txRef,
                TransactionType.PAYMENT_CAPTURE,
                "Payment capture for " + payment.getId() + " (processor ref: " + processorTxId + ")",
                List.of(
                        new PostTransactionCommand.PostEntryCommand(customerAccount.getId(), money.negate()),
                        new PostTransactionCommand.PostEntryCommand(merchantAccount.getId(), money)
                )
        );

        ledgerService.postTransaction(command);

        PaymentResponse response = PaymentResponse.from(payment);

        // 4. Atomically write to Transactional Outbox within same DB transaction
        outboxService.recordEvent("PAYMENT", payment.getId().toString(), "PAYMENT_CAPTURED", response);

        return response;
    }

    public PaymentResponse reversePayment(UUID paymentId, String reason) {
        return transactionTemplate.execute(status -> {
            Payment payment = paymentRepository.findById(paymentId)
                    .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

            payment.transitionTo(PaymentStatus.REVERSED);
            paymentRepository.save(payment);

            String originalTxRef = "PAYMENT-" + payment.getId();
            String reversalTxRef = "REVERSAL-" + payment.getId();
            ledgerService.reverseTransaction(originalTxRef, reversalTxRef, reason);

            PaymentResponse response = PaymentResponse.from(payment);
            outboxService.recordEvent("PAYMENT", payment.getId().toString(), "PAYMENT_REVERSED", response);

            return response;
        });
    }

    public record PaymentResult(int statusCode, PaymentResponse response, boolean replayed) {}
    public record PaymentErrorResponse(String code, String message) {}

    public static class PaymentDeclinedException extends RuntimeException {
        public PaymentDeclinedException(String message) {
            super(message);
        }
    }

    private String serializePayload(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize payload", e);
        }
    }

    private PaymentResponse deserializeResponse(String json) {
        try {
            return objectMapper.readValue(json, PaymentResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to deserialize cached response: " + json, e);
        }
    }
}
