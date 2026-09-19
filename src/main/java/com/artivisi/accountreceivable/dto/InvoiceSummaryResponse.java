package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Invoice list-row for the paginated {@code GET /api/invoices} endpoint — lightweight (no lines /
 * installments; fetch those via {@code GET /api/invoices/{id}}). {@code overdue} / {@code daysOverdue}
 * are resolved against the request date so the internal {@code earliestUnpaidDueDate} column stays
 * an implementation detail.
 */
public record InvoiceSummaryResponse(
        String id,
        String invoiceNumber,
        String debtorCode,
        String invoiceTypeCode,
        LocalDate dueDate,
        BigDecimal amount,
        BigDecimal outstanding,
        PaymentStatus paymentStatus,
        boolean overdue,
        long daysOverdue
) {
    public static InvoiceSummaryResponse from(InvoiceListItem i, LocalDate today) {
        return new InvoiceSummaryResponse(
                i.id(), i.invoiceNumber(), i.debtorCode(), i.invoiceTypeCode(),
                i.dueDate(), i.amount(), i.outstanding(), i.paymentStatus(),
                i.overdue(today), i.daysOverdue(today));
    }
}
