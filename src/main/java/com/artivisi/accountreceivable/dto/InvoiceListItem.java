package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Lightweight invoice-list row projected directly in SQL (constructor expression) — no line or
 * installment hydration. {@code earliestUnpaidDueDate} carries the denormalized collection-due date
 * (see V3 migration) so overdue / days-overdue are computed against a query-time {@code today}
 * without touching the installment rows.
 */
public record InvoiceListItem(
        String id,
        String invoiceNumber,
        String debtorCode,
        String invoiceTypeCode,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal outstanding,
        PaymentStatus paymentStatus,
        LocalDate earliestUnpaidDueDate
) {

    public boolean overdue(LocalDate today) {
        return earliestUnpaidDueDate != null && earliestUnpaidDueDate.isBefore(today);
    }

    public long daysOverdue(LocalDate today) {
        return overdue(today) ? ChronoUnit.DAYS.between(earliestUnpaidDueDate, today) : 0L;
    }
}
