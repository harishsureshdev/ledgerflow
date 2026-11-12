package com.ledgerflow.processor.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.audit.AuditLogService;
import com.ledgerflow.payments.domain.Payment;
import com.ledgerflow.payments.domain.PaymentStatus;
import com.ledgerflow.payments.repository.PaymentRepository;
import com.ledgerflow.payments.service.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class WebhookService {

    private static final Logger log = LoggerFactory.getLogger(WebhookService.class);

    private final ProcessorEventRepository processorEventRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentService paymentService;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public WebhookService(
            ProcessorEventRepository processorEventRepository,
            PaymentRepository paymentRepository,
            PaymentService paymentService,
            AuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Value("${ledgerflow.processor.webhook-secret:whsec_test_secret_key_12345}") String webhookSecret
    ) {
        this.processorEventRepository = processorEventRepository;
        this.paymentRepository = paymentRepository;
        this.paymentService = paymentService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    /**
     * Verifies HMAC signature, dedupes by processor event ID, and processes webhook.
     */
    @Transactional
    public WebhookResult processWebhook(String rawPayload, String signature, String eventId, String eventType) {
        // 1. Verify HMAC Signature
        if (!HmacSigner.verifySignature(rawPayload, signature, webhookSecret)) {
            log.warn("HMAC signature verification failed for webhook eventId={}", eventId);
            throw new SecurityException("Invalid HMAC signature");
        }

        // 2. Deduplicate: if event already processed, return 2xx without re-applying side effects
        if (processorEventRepository.existsByProcessorEventId(eventId)) {
            log.info("Deduplication: Webhook processor_event_id={} already processed. Returning 200 OK.", eventId);
            return new WebhookResult(true, "Duplicate webhook ignored");
        }

        // 3. Save processor event record
        ProcessorEventRecord eventRecord = new ProcessorEventRecord(eventId, eventType, rawPayload, signature);
        processorEventRepository.save(eventRecord);

        // 4. Audit Log
        auditLogService.log("SIM_PROCESSOR", "WEBHOOK", "WEBHOOK_RECEIVED", "PROCESSOR_EVENT", eventId, "type=" + eventType);

        // 5. Apply Domain Event Transitions
        try {
            JsonNode root = objectMapper.readTree(rawPayload);
            String paymentIdStr = root.path("paymentId").asText(null);

            if (paymentIdStr != null && !paymentIdStr.isBlank()) {
                UUID paymentId = UUID.fromString(paymentIdStr);
                if ("charge.settled".equalsIgnoreCase(eventType)) {
                    paymentRepository.findById(paymentId).ifPresent(p -> {
                        p.transitionTo(PaymentStatus.SETTLED);
                        paymentRepository.save(p);
                    });
                } else if ("charge.refunded".equalsIgnoreCase(eventType)) {
                    paymentService.reversePayment(paymentId, "Processor webhook charge.refunded");
                }
            }
        } catch (Exception e) {
            log.warn("Webhook domain application warning for eventId={}: {}", eventId, e.getMessage());
        }

        return new WebhookResult(false, "Webhook processed successfully");
    }

    public record WebhookResult(boolean isDuplicate, String message) {}
}
