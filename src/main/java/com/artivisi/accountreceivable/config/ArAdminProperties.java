package com.artivisi.accountreceivable.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Bootstrap admin credentials for the admin UI. Required; missing values fail startup (fail loud).
 * An admin user is created from these on first start if absent.
 */
@Validated
@ConfigurationProperties(prefix = "ar.admin")
public record ArAdminProperties(
        @NotBlank String username,
        @NotBlank String password
) {
}
