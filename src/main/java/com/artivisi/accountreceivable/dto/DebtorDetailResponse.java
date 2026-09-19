package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.DebtorStatus;
import com.artivisi.accountreceivable.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Debtor detail page: header stats, a chronological statement ledger for the requested trailing
 * window, and the currently open invoices. Pure data shaping; see
 * {@link com.artivisi.accountreceivable.service.DebtorLedgerService} for the computation.
 */
public record DebtorDetailResponse(
        String code,
        String name,
        String email,
        String phone,
        DebtorStatus status,
        BigDecimal outstandingTotal,
        long openInvoiceCount,
        BigDecimal overdueTotal,
        long oldestOverdueDays,
        BigDecimal totalPaid12Months,
        Double averageDaysToPay,
        int monthsBack,
        List<LedgerEntry> ledger,
        List<OpenInvoice> openInvoices
) {

    /** One statement row: either a debit (invoice issued) or a credit (payment applied), never both. */
    public record LedgerEntry(
            LocalDate date,
            String reference,
            String description,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal runningBalance
    ) {
    }

    public record OpenInvoice(
            String id,
            String invoiceNumber,
            String invoiceTypeCode,
            LocalDate dueDate,
            BigDecimal outstanding,
            PaymentStatus paymentStatus,
            boolean overdue,
            long overdueDays
    ) {
    }
}
