package com.ledgerflow.processor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny real HTTP card processor for network-fault tests, run in the test JVM and reached
 * through Toxiproxy. Behaves like sim-processor's POST /v1/charges: idempotent per key (a repeat
 * replays the original charge), and it records how many distinct charges it actually made, so
 * tests can prove "charged exactly once".
 */
public final class FakeCardNetwork implements AutoCloseable {

    private final HttpServer server;
    private final ObjectMapper json = new ObjectMapper();
    private final Map<String, String> chargesByKey = new ConcurrentHashMap<>();
    private final AtomicInteger requests = new AtomicInteger();

    public FakeCardNetwork() throws IOException {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", 0), 0);
        server.createContext("/v1/charges", exchange -> {
            requests.incrementAndGet();
            JsonNode req = json.readTree(exchange.getRequestBody());
            String key = req.path("idempotencyKey").asText();
            String chargeId = chargesByKey.computeIfAbsent(key, k -> "ch_fake_" + UUID.randomUUID());
            byte[] body = json.writeValueAsBytes(Map.of(
                    "processorTransactionId", chargeId,
                    "status", "APPROVED",
                    "amount", req.path("amount").asLong(),
                    "currency", req.path("currency").asText(),
                    "processedAt", Instant.now().toString()));
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Distinct charges made (repeat requests with a known key do not count). */
    public int chargesMade() {
        return chargesByKey.size();
    }

    public int requestsReceived() {
        return requests.get();
    }

    public void reset() {
        chargesByKey.clear();
        requests.set(0);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
