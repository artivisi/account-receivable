package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.NotificationChannel;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Start a dunning run over a channel, targeting invoices at least {@code minDaysOverdue} past due. */
public record DunningRunRequest(
        @NotNull NotificationChannel channel,
        @Min(0) int minDaysOverdue
) {
}
