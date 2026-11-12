package com.ledgerflow.processor.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/chaos")
public class ChaosProxyController {

    private static final Logger log = LoggerFactory.getLogger(ChaosProxyController.class);
    private final RestClient restClient;
    private final String processorBaseUrl;

    public record ChaosDto(long latencyMs, double failureRate, double duplicateRate) {}

    public ChaosProxyController(
            @Value("${ledgerflow.processor.base-url:http://localhost:8081}") String processorBaseUrl
    ) {
        this.processorBaseUrl = processorBaseUrl;
        this.restClient = RestClient.builder().baseUrl(processorBaseUrl).build();
    }

    @GetMapping("/config")
    public ResponseEntity<?> getChaosConfig() {
        try {
            ChaosDto config = restClient.get()
                    .uri("/chaos/config")
                    .retrieve()
                    .body(ChaosDto.class);
            return ResponseEntity.ok(config);
        } catch (Exception e) {
            log.warn("sim-processor unreachable at {}: {}", processorBaseUrl, e.getMessage());
            return ResponseEntity.ok(new ChaosDto(0, 0.0, 0.0));
        }
    }

    @PostMapping("/config")
    public ResponseEntity<?> updateChaosConfig(@RequestBody ChaosDto config) {
        try {
            ChaosDto updated = restClient.post()
                    .uri("/chaos/config")
                    .body(config)
                    .retrieve()
                    .body(ChaosDto.class);
            return ResponseEntity.ok(updated);
        } catch (Exception e) {
            log.warn("Failed to update chaos config on {}: {}", processorBaseUrl, e.getMessage());
            return ResponseEntity.ok(config);
        }
    }
}
