package com.artivisi.accountreceivable.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Required security config. A missing {@code ar.security.secret-key}
 * fails startup explicitly (fail loud, no default key).
 */
@Validated
@ConfigurationProperties(prefix = "ar.security")
public record ArSecurityProperties(@NotBlank String secretKey) {
}
