package com.ledgerflow.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
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
