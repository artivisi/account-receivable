package com.artivisi.accountreceivable.config;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 over a webhook body, hex-encoded, matching the gateway's signing scheme.
 * Verification is constant-time.
 */
@Component
public class HmacSignature {

    private static final String ALGORITHM = "HmacSHA256";

    public String hex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to compute HMAC signature", e);
        }
    }

    public boolean verify(String secret, String body, String providedHex) {
        if (providedHex == null) {
            return false;
        }
        String expected = hex(secret, body);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                providedHex.getBytes(StandardCharsets.UTF_8));
    }
}
