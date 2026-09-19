package com.artivisi.accountreceivable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record InvoiceTypeRequest(
        @NotBlank String code,
        @NotBlank String name,
        @NotNull Boolean active
) {
}
