package com.ledgerflow.processor.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HmacSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private HmacSigner() {
    }

    public static String calculateSignature(String payload, String secret) {
        if (payload == null) {
            payload = "";
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            SecretKeySpec secretKeySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256);
            mac.init(secretKeySpec);
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to calculate HMAC signature", e);
        }
    }

    public static boolean verifySignature(String payload, String headerSignature, String secret) {
        if (headerSignature == null || secret == null) {
            return false;
        }
        String expected = calculateSignature(payload, secret);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                headerSignature.getBytes(StandardCharsets.UTF_8)
        );
    }
}
