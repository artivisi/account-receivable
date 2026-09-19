package com.artivisi.accountreceivable.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record CreditNoteRequest(
        @NotBlank String invoiceId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        String reason
) {
}
