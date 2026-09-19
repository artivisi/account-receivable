package com.artivisi.accountreceivable.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/** Replace the unpaid part of an instalment plan. The legs must sum to what is still outstanding. */
public record AmendPlanRequest(
        @Valid @NotEmpty List<IssueInvoiceRequest.InstallmentRequest> installments,
        @NotBlank String reason
) {
}
