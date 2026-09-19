package com.artivisi.accountreceivable.spi;

import com.artivisi.accountreceivable.entity.NotificationChannel;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Structured reminder data handed to a {@link NotificationSender}. The engine passes facts, not
 * rendered text — message templates are a deployment concern (the sender renders + delivers).
 */
public record DunningNotification(
        NotificationChannel channel,
        String recipient,
        String debtorName,
        String invoiceNumber,
        String currency,
        BigDecimal amountOutstanding,
        LocalDate dueDate,
        long daysOverdue
) {
}
