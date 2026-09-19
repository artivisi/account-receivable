package com.artivisi.accountreceivable.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Payment-gateway consumer config. All required; missing values fail startup (fail loud).
 * {@code clientSecret} authenticates outbound charge calls and verifies inbound webhook signatures
 * (the gateway signs webhooks with the consumer secret). The retry/poll fields tune the outbound
 * charge-cancellation outbox.
 */
@Validated
@ConfigurationProperties(prefix = "ar.gateway")
public record ArGatewayProperties(
        @NotBlank String baseUrl,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String escrowCode,
        @Min(1) int maxAttempts,
        @Min(0) long backoffBaseSeconds,
        @Min(1) long pollIntervalMs,
        // VA encoding — required only when using the built-in EncodedVaNumberSupplier.
        String vaPrefix,
        Integer vaDigitLength,
        Integer vaInvoiceTypeDigits
) {
}
