package com.ledgerflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Redis is only for rate limiting (design principle 2). When it is unreachable, payments must
 * keep working: the limiter fails open, quickly, and counts the error.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ledgerflow.ratelimit.payments.capacity=1",
        "ledgerflow.ratelimit.payments.refill-per-minute=1"
})
class PaymentRateLimitRedisDownIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountProvisioningService accountProvisioningService;
    @Autowired private MeterRegistry meters;

    @Test
    @DisplayName("With Redis down, payments still succeed (fail-open) even past the configured limit")
    void failsOpen() throws Exception {
        String user = "cust_" + UUID.randomUUID();
        accountProvisioningService.getOrCreateCustomerAccount(user, "USD", 100_000L);
        String body = objectMapper.writeValueAsString(new CreatePaymentRequest(user, 100L, "USD", "tok_visa_4242"));

        // Freeze Redis: commands hang rather than fail fast (the nastier real-world failure)
        var docker = redis.getDockerClient();
        docker.pauseContainerCmd(redis.getContainerId()).exec();
        long start = System.nanoTime();
        try {
            for (int i = 0; i < 3; i++) { // limit is 1, so with a working Redis requests 2 and 3 would be 429
                mockMvc.perform(post("/api/v1/payments")
                                .header("Idempotency-Key", "key_" + UUID.randomUUID())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body)
                                .with(jwt().jwt(j -> j.subject(user)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                        .andExpect(status().isCreated());
            }
        } finally {
            docker.unpauseContainerCmd(redis.getContainerId()).exec();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(meters.counter("ledgerflow.ratelimit.errors").count()).isGreaterThanOrEqualTo(3);
        assertThat(elapsedMs).as("a dead Redis must not stall payments").isLessThan(10_000);
    }
}
