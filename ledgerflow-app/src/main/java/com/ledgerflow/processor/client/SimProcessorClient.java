package com.ledgerflow.processor.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.time.Duration;

@Component
public class SimProcessorClient implements ProcessorClient {

    private static final Logger log = LoggerFactory.getLogger(SimProcessorClient.class);
    private final RestClient restClient;

    public SimProcessorClient(
            @Value("${ledgerflow.processor.base-url:http://localhost:8081}") String baseUrl
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Calls external card processor outside of any database transaction.
     * Protected by Resilience4j Retry, Circuit Breaker, and Timeouts.
     */
    @CircuitBreaker(name = "simProcessor", fallbackMethod = "chargeFallback")
    @Retry(name = "simProcessor")
    public ProcessorChargeResult authorizeAndCapture(ProcessorChargeCommand command) {
        log.info("Calling card processor for idempotencyKey={}, amount={}", command.idempotencyKey(), command.amount());

        return restClient.post()
                .uri("/v1/charges")
                .contentType(MediaType.APPLICATION_JSON)
                .body(command)
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError, (req, resp) -> {
                    throw new HttpClientErrorException(resp.getStatusCode(), "Card processor client error: " + resp.getStatusText());
                })
                .onStatus(HttpStatusCode::is5xxServerError, (req, resp) -> {
                    throw new HttpServerErrorException(resp.getStatusCode(), "Card processor server error: " + resp.getStatusText());
                })
                .body(ProcessorChargeResult.class);
    }

    /**
     * Fallback invoked when the circuit breaker is OPEN or retries are exhausted.
     */
    public ProcessorChargeResult chargeFallback(ProcessorChargeCommand command, Throwable ex) {
        log.error("Card processor call failed (fallback activated) for key={}: {}", command.idempotencyKey(), ex.getMessage());
        return new ProcessorChargeResult(
                null,
                "FAILED",
                command.amount(),
                command.currency(),
                "Processor unavailable: " + ex.getMessage(),
                null
        );
    }
}
