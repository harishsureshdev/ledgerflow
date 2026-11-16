package com.ledgerflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Payment creation is rate limited per authenticated user with a Redis token bucket.
 * Capacity 3, refilling very slowly, so the 4th request in a burst must be rejected.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "ledgerflow.ratelimit.payments.capacity=3",
        "ledgerflow.ratelimit.payments.refill-per-minute=1"
})
class PaymentRateLimitIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountProvisioningService accountProvisioningService;

    private ResultActions createPayment(String user) throws Exception {
        accountProvisioningService.getOrCreateCustomerAccount(user, "USD", 100_000L);
        String body = objectMapper.writeValueAsString(new CreatePaymentRequest(user, 100L, "USD", "tok_visa_4242"));
        return mockMvc.perform(post("/api/v1/payments")
                .header("Idempotency-Key", "key_" + UUID.randomUUID())
                .header("X-Merchant-Id", "merch_" + UUID.randomUUID()) // varying the merchant header must not reset the limit
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(jwt().jwt(j -> j.subject(user)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
    }

    @Test
    @DisplayName("4th payment in a burst gets 429 problem+json with Retry-After; other users are unaffected")
    void burstIsLimitedPerUser() throws Exception {
        String alice = "cust_alice_" + UUID.randomUUID();
        String bob = "cust_bob_" + UUID.randomUUID();

        for (int i = 0; i < 3; i++) {
            createPayment(alice).andExpect(status().isCreated()).andExpect(header().exists("X-RateLimit-Remaining"));
        }

        createPayment(alice)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(header().exists("Retry-After"));

        createPayment(bob).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Reads are not rate limited, only payment creation")
    void readsAreNotLimited() throws Exception {
        String carol = "cust_carol_" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) createPayment(carol).andExpect(status().isCreated());
        createPayment(carol).andExpect(status().isTooManyRequests());

        mockMvc.perform(get("/api/v1/payments")
                        .with(jwt().jwt(j -> j.subject(carol)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk());
    }
}
