package com.artivisi.accountreceivable.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Withdraw an invoice that should never have been collectible. {@code reason} is one of the
 * contract's cancellation reasons (SUPERSEDED, DUPLICATE, ISSUED_IN_ERROR, WITHDRAWN);
 * {@code replacedBy} names the replacement when SUPERSEDED.
 */
public record CancelInvoiceRequest(@NotBlank String reason, String replacedBy, String note) {
}
