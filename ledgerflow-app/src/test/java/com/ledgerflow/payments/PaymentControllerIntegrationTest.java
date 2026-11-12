package com.ledgerflow.payments;

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
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class PaymentControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountProvisioningService accountProvisioningService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("POST /api/v1/payments returns 201 Created and subsequent call replays identical response")
    void endToEndPaymentWithIdempotency() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();

        accountProvisioningService.getOrCreateCustomerAccount(customerId, "USD", 50000L);

        CreatePaymentRequest request = new CreatePaymentRequest(
                customerId,
                3500L,
                "USD",
                "tok_visa_4242"
        );
        String requestJson = objectMapper.writeValueAsString(request);

        // First POST
        String responseBody = mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .with(jwt().jwt(j -> j.subject(customerId))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(3500))
                .andReturn().getResponse().getContentAsString();

        // Replay with exact same key and payload
        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson)
                        .with(jwt().jwt(j -> j.subject(customerId))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(3500));
    }

    @Test
    @DisplayName("POST /api/v1/payments returns 422 ProblemDetail on payload mismatch for same key")
    void payloadMismatchReturns422ProblemDetail() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();
        String customerId = "cust_" + UUID.randomUUID();
        String idempotencyKey = "key_" + UUID.randomUUID();

        accountProvisioningService.getOrCreateCustomerAccount(customerId, "USD", 50000L);

        CreatePaymentRequest firstRequest = new CreatePaymentRequest(
                customerId,
                1000L,
                "USD",
                "tok_visa_4242"
        );
        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(firstRequest))
                        .with(jwt().jwt(j -> j.subject(customerId))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isCreated());

        // Different amount
        CreatePaymentRequest modifiedRequest = new CreatePaymentRequest(
                customerId,
                9999L,
                "USD",
                "tok_visa_4242"
        );

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", idempotencyKey)
                        .header("X-Merchant-Id", merchantId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(modifiedRequest))
                        .with(jwt().jwt(j -> j.subject(customerId))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.type").value("https://ledgerflow.io/errors/idempotency-payload-mismatch"));
    }
}
