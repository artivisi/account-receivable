package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.CreditNote;

import java.math.BigDecimal;
import java.time.LocalDate;

public record CreditNoteResponse(
        String id,
        String creditNoteNumber,
        String invoiceId,
        String invoiceNumber,
        String debtorCode,
        LocalDate issueDate,
        String currency,
        BigDecimal amount,
        BigDecimal invoiceOutstanding,
        String reason
) {
    public static CreditNoteResponse from(CreditNote c) {
        return new CreditNoteResponse(
                c.getId(), c.getCreditNoteNumber(), c.getInvoice().getId(),
                c.getInvoice().getInvoiceNumber(), c.getDebtor().getCode(), c.getIssueDate(),
                c.getCurrency(), c.getAmount(), c.getInvoice().getOutstanding(), c.getReason());
    }
}
