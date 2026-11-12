package com.ledgerflow.processor.webhook;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.audit.AuditLogEntry;
import com.ledgerflow.audit.AuditLogRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ProcessorEventRepository processorEventRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Value("${ledgerflow.processor.webhook-secret:whsec_test_secret_key_12345}")
    private String webhookSecret;

    @Test
    @DisplayName("HMAC Webhooks: Valid signature processes event, duplicates return 2xx without side effects, invalid signature rejected")
    void webhookVerificationAndDeduplication() {
        String eventId = "evt_" + UUID.randomUUID();
        String payload = "{\"paymentId\": \"" + UUID.randomUUID() + "\", \"status\": \"SETTLED\"}";
        String validSignature = HmacSigner.calculateSignature(payload, webhookSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Signature", validSignature);
        headers.set("X-Processor-Event-Id", eventId);
        headers.set("X-Event-Type", "charge.settled");
        headers.setContentType(MediaType.APPLICATION_JSON);

        // 1. Valid webhook call succeeds
        ResponseEntity<String> firstResponse = restTemplate.postForEntity(
                "/api/v1/webhooks/processor",
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(firstResponse.getBody()).contains("success");
        assertThat(firstResponse.getBody()).contains("\"duplicate\":false");

        // Verify saved in processor_events table
        assertThat(processorEventRepository.existsByProcessorEventId(eventId)).isTrue();

        // Verify audit log entry was written
        List<AuditLogEntry> auditEntries = auditLogRepository.findByResourceTypeAndResourceIdOrderByTimestampDesc("PROCESSOR_EVENT", eventId);
        assertThat(auditEntries).isNotEmpty();

        // 2. Duplicate webhook with same eventId returns 2xx and is marked duplicate
        ResponseEntity<String> duplicateResponse = restTemplate.postForEntity(
                "/api/v1/webhooks/processor",
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(duplicateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(duplicateResponse.getBody()).contains("\"duplicate\":true");

        // 3. Invalid signature is rejected
        headers.set("X-Signature", "invalid_tampered_signature_hex");
        ResponseEntity<String> rejectedResponse = restTemplate.postForEntity(
                "/api/v1/webhooks/processor",
                new HttpEntity<>(payload, headers),
                String.class
        );

        assertThat(rejectedResponse.getStatusCode()).isNotEqualTo(HttpStatus.OK);
    }
}
