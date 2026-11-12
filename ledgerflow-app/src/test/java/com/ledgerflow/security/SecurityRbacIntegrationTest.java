package com.ledgerflow.security;

import com.ledgerflow.AbstractIntegrationTest;
import com.ledgerflow.audit.AuditLogEntry;
import com.ledgerflow.audit.AuditLogRepository;
import com.ledgerflow.payments.dto.CreatePaymentRequest;
import com.ledgerflow.payments.dto.PaymentResponse;
import com.ledgerflow.payments.service.PaymentService;
import com.ledgerflow.recon.domain.MismatchType;
import com.ledgerflow.recon.domain.ReconItem;
import com.ledgerflow.recon.domain.SettlementFile;
import com.ledgerflow.recon.repository.ReconItemRepository;
import com.ledgerflow.recon.repository.SettlementFileRepository;
import com.ledgerflow.recon.service.AdjustmentService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class SecurityRbacIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private AdjustmentService adjustmentService;

    @Autowired
    private SettlementFileRepository settlementFileRepository;

    @Autowired
    private ReconItemRepository reconItemRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Test
    @DisplayName("RBAC: Customer can view their own payment, but is FORBIDDEN from viewing another customer's payment")
    void customerCannotViewOthersData() throws Exception {
        String merchantId = "merch_" + UUID.randomUUID();

        // Alice's payment
        PaymentResponse alicePayment = paymentService.processPayment(
                merchantId, "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_alice", 1500L, "USD", "tok_visa_4242")
        ).response();

        // Bob's payment
        PaymentResponse bobPayment = paymentService.processPayment(
                merchantId, "key_" + UUID.randomUUID(),
                new CreatePaymentRequest("cust_bob", 2500L, "USD", "tok_visa_4242")
        ).response();

        // 1. Alice viewing Alice's payment -> 200 OK
        mockMvc.perform(get("/api/v1/payments/" + alicePayment.id())
                        .with(jwt().jwt(j -> j.subject("cust_alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerId").value("cust_alice"));

        // 2. Alice viewing Bob's payment -> 403 Forbidden!
        mockMvc.perform(get("/api/v1/payments/" + bobPayment.id())
                        .with(jwt().jwt(j -> j.subject("cust_alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("RBAC: Only FINANCE_ADMIN can approve financial adjustments; OPS_AGENT is rejected (HTTP 403)")
    void onlyFinanceAdminCanApproveAdjustments() throws Exception {
        SettlementFile file = settlementFileRepository.save(
                new SettlementFile("test_recon_" + UUID.randomUUID() + ".csv", 1, 1000L, "USD"));
        ReconItem item = reconItemRepository.save(
                new ReconItem(file, MismatchType.AMOUNT_MISMATCH, "ref_123", 1000L, 800L, "USD"));

        // 1. Non-admin (OPS_AGENT) attempts approval -> 403 Forbidden
        mockMvc.perform(post("/api/v1/adjustments/approve")
                        .param("reconItemId", item.getId().toString())
                        .with(jwt().jwt(j -> j.subject("agent_smith"))
                                .authorities(new SimpleGrantedAuthority("ROLE_OPS_AGENT"))))
                .andExpect(status().isForbidden());

        // 2. Customer attempts approval -> 403 Forbidden
        mockMvc.perform(post("/api/v1/adjustments/approve")
                        .param("reconItemId", item.getId().toString())
                        .with(jwt().jwt(j -> j.subject("cust_alice"))
                                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());

        // 3. FINANCE_ADMIN approves -> 200 OK
        mockMvc.perform(post("/api/v1/adjustments/approve")
                        .param("reconItemId", item.getId().toString())
                        .param("reason", "Approved discrepancy per investigation")
                        .with(jwt().jwt(j -> j.subject("finance_chief"))
                                .authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"));

        // Verify status is resolved in DB and audit logged
        ReconItem reloaded = reconItemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("RESOLVED");

        List<AuditLogEntry> auditEntries = auditLogRepository.findByResourceTypeAndResourceIdOrderByTimestampDesc("RECON_ITEM", item.getId().toString());
        assertThat(auditEntries).isNotEmpty();
        assertThat(auditEntries.getFirst().getRole()).isEqualTo("FINANCE_ADMIN");
    }

    @Test
    @WithMockUser(username = "agent_jones", roles = {"OPS_AGENT"})
    @DisplayName("Service-Layer Security: AdjustmentService throws AccessDeniedException when caller lacks ROLE_FINANCE_ADMIN")
    void serviceLayerEnforcesFinanceAdminRole() {
        SettlementFile file = settlementFileRepository.save(
                new SettlementFile("test_recon_" + UUID.randomUUID() + ".csv", 1, 500L, "USD"));
        ReconItem item = reconItemRepository.save(
                new ReconItem(file, MismatchType.MISSING_IN_BANK, "ref_456", null, 500L, "USD"));

        // Called as OPS_AGENT
        assertThatThrownBy(() -> adjustmentService.approveAdjustment(item.getId(), "Agent trying to adjust"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
