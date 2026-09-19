package com.artivisi.accountreceivable.config;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static SecretCipher cipherWithRandomKey() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        return new SecretCipher(new ArSecurityProperties(key));
    }

    @Test
    void encryptThenDecrypt_roundTrips() {
        SecretCipher cipher = cipherWithRandomKey();
        String plaintext = "client-secret-value";

        String encrypted = cipher.encrypt(plaintext);

        assertThat(encrypted).isNotEqualTo(plaintext);
        assertThat(cipher.decrypt(encrypted)).isEqualTo(plaintext);
    }

    @Test
    void sameInput_producesDifferentCiphertext() {
        SecretCipher cipher = cipherWithRandomKey();

        assertThat(cipher.encrypt("x")).isNotEqualTo(cipher.encrypt("x"));
    }

    @Test
    void nonBase64Key_failsLoud() {
        assertThatThrownBy(() -> new SecretCipher(new ArSecurityProperties("not base64 !!!")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }

    @Test
    void wrongLengthKey_failsLoud() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new SecretCipher(new ArSecurityProperties(shortKey)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
