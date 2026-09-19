package com.artivisi.accountreceivable.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record InvoiceTypeVaCodeRequest(
        @NotBlank @Pattern(regexp = "\\d{1,2}", message = "vaCode must be 1 or 2 numeric digits")
        String vaCode
) {
}
