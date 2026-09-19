package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record DebtorStatementResponse(
        String debtorCode,
        String debtorName,
        String currency,
        BigDecimal totalAmount,
        BigDecimal totalOutstanding,
        List<Line> lines
) {

    public record Line(
            String invoiceNumber,
            LocalDate issueDate,
            LocalDate dueDate,
            BigDecimal amount,
            BigDecimal outstanding,
            PaymentStatus paymentStatus
    ) {
        public static Line from(Invoice i) {
            return new Line(i.getInvoiceNumber(), i.getIssueDate(), i.getDueDate(),
                    i.getAmount(), i.getOutstanding(), i.getPaymentStatus());
        }
    }
}
