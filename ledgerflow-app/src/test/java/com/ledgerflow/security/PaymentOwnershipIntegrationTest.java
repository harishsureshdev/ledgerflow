package com.ledgerflow.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.ledger.domain.Account;
import com.ledgerflow.ledger.repository.AccountRepository;
import com.ledgerflow.ledger.service.AccountProvisioningService;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.repository.PaymentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Bug: the customerId in the request body was trusted, so any customer could charge anyone's account. */
@AutoConfigureMockMvc
class PaymentOwnershipIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AccountProvisioningService accountProvisioningService;
    @Autowired private AccountRepository accountRepository;
    @Autowired private PaymentRepository paymentRepository;

    private String body(String customerId) throws Exception {
        return objectMapper.writeValueAsString(new CreatePaymentRequest(customerId, 900L, "USD", "tok_visa_4242"));
    }

    @Test
    @DisplayName("A customer cannot create a payment that debits another customer's account (403, no money moves)")
    void customerCannotChargeSomeoneElse() throws Exception {
        String alice = "cust_alice_" + UUID.randomUUID();
        String bob = "cust_bob_" + UUID.randomUUID();
        Account bobAccount = accountProvisioningService.getOrCreateCustomerAccount(bob, "USD", 50_000L);
        long bobBefore = bobAccount.getBalance();

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(bob)) // Alice's token, Bob's account
                        .with(jwt().jwt(j -> j.subject(alice)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());

        assertThat(accountRepository.findById(bobAccount.getId()).orElseThrow().getBalance()).isEqualTo(bobBefore);
        assertThat(paymentRepository.findByCustomerIdOrderByCreatedAtDesc(bob)).isEmpty();
    }

    @Test
    @DisplayName("Ops staff may create a payment on a customer's behalf")
    void opsAgentMayActOnBehalf() throws Exception {
        String carol = "cust_carol_" + UUID.randomUUID();
        accountProvisioningService.getOrCreateCustomerAccount(carol, "USD", 50_000L);

        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key_" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(carol))
                        .with(jwt().jwt(j -> j.subject("ops_kim")).authorities(new SimpleGrantedAuthority("ROLE_OPS_AGENT"))))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("A malformed X-Merchant-Id header is rejected with 400")
    void malformedMerchantIdRejected() throws Exception {
        String dave = "cust_dave_" + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/payments")
                        .header("Idempotency-Key", "key_" + UUID.randomUUID())
                        .header("X-Merchant-Id", "merch'; DROP TABLE payments;--")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(dave))
                        .with(jwt().jwt(j -> j.subject(dave)).authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isBadRequest());
    }
}
