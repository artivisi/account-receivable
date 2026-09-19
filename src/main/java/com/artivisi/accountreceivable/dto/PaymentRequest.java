package com.artivisi.accountreceivable.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * Phase-1 internal receipt against an invoice or installment. Superseded by idempotent cash
 * application (driven by gateway webhooks) in phase 2.
 */
public record PaymentRequest(
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount
) {
}
