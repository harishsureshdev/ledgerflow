package com.ledgerflow.processor.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
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

import java.net.ConnectException;
import java.time.Duration;

@Component
public class SimProcessorClient implements ProcessorClient {

    private static final Logger log = LoggerFactory.getLogger(SimProcessorClient.class);
    private final RestClient restClient;

    public SimProcessorClient(
            @Value("${ledgerflow.processor.base-url:http://localhost:8081}") String baseUrl,
            @Value("${ledgerflow.processor.connect-timeout:2s}") Duration connectTimeout,
            @Value("${ledgerflow.processor.read-timeout:2s}") Duration readTimeout
    ) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);

        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    /**
     * Calls external card processor outside of any database transaction.
     *
     * <p>Resilience4j order: Retry (outer) -> CircuitBreaker (inner) -> HTTP call with timeouts.
     * Retrying is safe because the processor deduplicates by {@code command.idempotencyKey()},
     * which stays the same across attempts. The circuit breaker has no fallback on purpose: a
     * fallback there swallowed every error before Retry could see it, so retries never ran.
     */
    @Retry(name = "simProcessor", fallbackMethod = "chargeFallback")
    @CircuitBreaker(name = "simProcessor")
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
     * Invoked once retries are exhausted (or immediately when the circuit is open).
     *
     * <p>A 4xx means the processor definitely refused the request: report a failure (no charge).
     * Anything else means we have no definite answer, so it must NOT be reported as a decline:
     * throw {@link ProcessorUnavailableException} and let the caller retry with the same key.
     */
    public ProcessorChargeResult chargeFallback(ProcessorChargeCommand command, Throwable ex) {
        if (ex instanceof HttpClientErrorException clientError) {
            log.warn("Card processor rejected request key={}: {}", command.idempotencyKey(), clientError.getStatusCode());
            return new ProcessorChargeResult(null, "FAILED", command.amount(), command.currency(),
                    "Processor rejected request: " + clientError.getStatusCode(), null);
        }
        boolean notAttempted = ex instanceof CallNotPermittedException
                || (ex instanceof ResourceAccessException && ex.getCause() instanceof ConnectException);
        log.error("Card processor unavailable for key={} (outcome {}): {}", command.idempotencyKey(),
                notAttempted ? "not attempted" : "UNKNOWN", ex.toString());
        throw new ProcessorUnavailableException(
                notAttempted ? "Card processor unavailable" : "Card processor did not answer in time; outcome unknown",
                !notAttempted, ex);
    }
}
