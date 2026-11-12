package com.ledgerflow.processor.webhook;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks/processor")
public class WebhookController {

    private final WebhookService webhookService;

    public WebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> handleWebhook(
            @RequestBody String rawPayload,
            @RequestHeader(value = "X-Signature", required = false) String signature,
            @RequestHeader(value = "X-Processor-Event-Id") String eventId,
            @RequestHeader(value = "X-Event-Type", defaultValue = "charge.settled") String eventType
    ) {
        WebhookService.WebhookResult result = webhookService.processWebhook(rawPayload, signature, eventId, eventType);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "duplicate", result.isDuplicate(),
                "message", result.message()
        ));
    }
}
