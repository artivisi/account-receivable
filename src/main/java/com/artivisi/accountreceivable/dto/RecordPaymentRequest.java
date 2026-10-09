package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.PaymentChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A payment taken outside the Payment Gateway, told to AR by whichever application received it.
 *
 * <p>{@code reference} is that application's own receipt number and becomes the payment's identity
 * here, in the {@code RECORDED} namespace — so it may legitimately equal a bank journal number.
 * {@code receivedAt} is when the money changed hands, not when this was sent: a receipt entered
 * three days late belongs in the cash takings of the day it was taken.
 */
public record RecordPaymentRequest(
        @NotBlank String invoiceId,
        @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
        @NotBlank String currency,
        @NotNull Instant receivedAt,
        @NotNull PaymentChannel channel,
        @NotBlank String reference,
        String reason,
        String decisionReference
) {
}
