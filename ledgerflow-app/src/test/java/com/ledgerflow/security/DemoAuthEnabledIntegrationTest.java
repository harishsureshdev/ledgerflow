package com.ledgerflow.security;

import com.ledgerflow.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** With demo mode explicitly on (docker-compose demo), the persona switcher can still get tokens. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "ledgerflow.demo-auth.enabled=true")
class DemoAuthEnabledIntegrationTest extends AbstractIntegrationTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("Demo mode issues persona tokens")
    void demoTokenWorksInDemoMode() throws Exception {
        mockMvc.perform(post("/api/v1/auth/demo-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"cust_alice\",\"role\":\"ROLE_CUSTOMER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
    }
}
