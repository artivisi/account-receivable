package com.artivisi.accountreceivable.config;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA converter that transparently encrypts secret columns at rest using {@link SecretCipher}.
 * Applied explicitly via {@code @Convert} on credential fields. If the cipher is not yet
 * initialized, conversion fails loudly rather than persisting plaintext.
 */
@Converter
public class SecretConverter implements AttributeConverter<String, String> {

    private static volatile SecretCipher cipher;

    static void register(SecretCipher secretCipher) {
        cipher = secretCipher;
    }

    private static SecretCipher cipher() {
        SecretCipher current = cipher;
        if (current == null) {
            throw new IllegalStateException("SecretCipher not initialized; cannot read/write encrypted column");
        }
        return current;
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        return attribute == null ? null : cipher().encrypt(attribute);
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        return dbData == null ? null : cipher().decrypt(dbData);
    }
}
