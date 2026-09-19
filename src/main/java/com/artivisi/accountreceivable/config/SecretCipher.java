package com.artivisi.accountreceivable.config;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for credentials persisted in the registries (e.g. GL / gateway
 * client secrets). The key comes from {@link ArSecurityProperties}; an absent or wrong-length
 * key fails startup (fail loud). Never logs key material or plaintext.
 */
@Component
public class SecretCipher {

    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public SecretCipher(ArSecurityProperties properties) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(properties.secretKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("ar.security.secret-key must be Base64-encoded", e);
        }
        if (decoded.length != 32) {
            throw new IllegalStateException(
                    "ar.security.secret-key must decode to 32 bytes (AES-256); got " + decoded.length);
        }
        this.key = new SecretKeySpec(decoded, ALGORITHM);
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(combined);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt secret", e);
        }
    }

    public String decrypt(String stored) {
        try {
            byte[] combined = Base64.getDecoder().decode(stored);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[combined.length - IV_LENGTH_BYTES];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(combined, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt secret", e);
        }
    }

    @PostConstruct
    void register() {
        SecretConverter.register(this);
    }
}
