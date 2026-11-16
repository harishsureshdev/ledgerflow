package com.ledgerflow.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * DEMO ONLY: issues a signed JWT for any persona/role so the dashboard can switch users.
 * It lets the caller pick ANY role (including FINANCE_ADMIN), so it is OFF unless
 * {@code ledgerflow.demo-auth.enabled=true} (set by docker-compose for the local demo).
 * In production, tokens come from a real identity provider.
 */
@RestController
@RequestMapping("/api/v1/auth")
@ConditionalOnProperty(name = "ledgerflow.demo-auth.enabled", havingValue = "true")
public class DemoAuthController {

    private final DemoTokenService demoTokenService;

    public DemoAuthController(DemoTokenService demoTokenService) {
        this.demoTokenService = demoTokenService;
    }

    public record DemoTokenRequest(String username, String role) {}
    public record DemoTokenResponse(String accessToken, String tokenType, String username, String role) {}

    @PostMapping("/demo-token")
    public ResponseEntity<DemoTokenResponse> getDemoToken(@RequestBody DemoTokenRequest request) {
        String username = request.username() != null && !request.username().isBlank()
                ? request.username() : "demo_user";
        String role = request.role() != null && !request.role().isBlank()
                ? request.role() : "ROLE_CUSTOMER";

        String token = demoTokenService.generateToken(username, role);
        return ResponseEntity.ok(new DemoTokenResponse(token, "Bearer", username, role));
    }
}
