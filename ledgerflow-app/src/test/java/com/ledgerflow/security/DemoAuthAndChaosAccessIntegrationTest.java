package com.ledgerflow.security;

import com.ledgerflow.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bugs: (1) the public demo-token endpoint minted a token for ANY role, so anyone could become
 * FINANCE_ADMIN and approve ledger adjustments; (2) the chaos controls were public, so anyone
 * could degrade the card processor connection.
 */
@AutoConfigureMockMvc
class DemoAuthAndChaosAccessIntegrationTest extends AbstractIntegrationTest {

    private static final String FINANCE_ADMIN_REQUEST = "{\"username\":\"mallory\",\"role\":\"ROLE_FINANCE_ADMIN\"}";

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Demo token endpoint is OFF by default: nobody can mint a FINANCE_ADMIN token")
    void demoTokenDisabledByDefault() throws Exception {
        mockMvc.perform(post("/api/v1/auth/demo-token").contentType(MediaType.APPLICATION_JSON).content(FINANCE_ADMIN_REQUEST))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("Chaos controls require OPS_AGENT or FINANCE_ADMIN")
    void chaosRequiresStaffRole() throws Exception {
        String config = "{\"latencyMs\":5000,\"failureRate\":1.0,\"duplicateRate\":0}";
        mockMvc.perform(post("/api/v1/chaos/config").contentType(MediaType.APPLICATION_JSON).content(config))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/chaos/config").contentType(MediaType.APPLICATION_JSON).content(config)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/chaos/config")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPS_AGENT"))))
                .andExpect(status().isOk());
    }
}
