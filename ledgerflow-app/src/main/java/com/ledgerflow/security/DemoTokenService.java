package com.ledgerflow.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Service
public class DemoTokenService {

    private final String jwtSecret;

    public DemoTokenService(
            @Value("${ledgerflow.jwt.secret:ledgerflow-super-secret-jwt-signing-key-for-hmac-sha256-which-is-at-least-256-bits}")
            String jwtSecret
    ) {
        this.jwtSecret = jwtSecret;
    }

    public String generateToken(String username, String role) {
        try {
            String roleWithPrefix = role.startsWith("ROLE_") ? role : "ROLE_" + role;
            JWSHeader header = new JWSHeader(JWSAlgorithm.HS256);
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(username)
                    .claim("role", roleWithPrefix)
                    .claim("roles", List.of(roleWithPrefix))
                    .issueTime(new Date())
                    .expirationTime(new Date(System.currentTimeMillis() + 86400000)) // 24 hours
                    .build();

            SignedJWT signedJWT = new SignedJWT(header, claims);
            signedJWT.sign(new MACSigner(jwtSecret.getBytes(StandardCharsets.UTF_8)));
            return signedJWT.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to mint demo token", e);
        }
    }
}
