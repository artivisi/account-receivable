package com.artivisi.accountreceivable.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import com.artivisi.accountreceivable.entity.CreditReason;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * {@code reasonCode} is required: a credit whose kind nobody stated cannot be reported, and guessing
 * one would put a scholarship and a mispricing in the same bucket. {@code reference} names the
 * decision behind it, and is required for a scholarship.
 */
public record CreditNoteRequest(
        @NotBlank String invoiceId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotNull CreditReason reasonCode,
        String reference,
        String reason
) {
}
