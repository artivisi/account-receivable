package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.CashApplicationLine;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import com.artivisi.accountreceivable.entity.Installment;
import com.artivisi.accountreceivable.entity.Invoice;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Admin cash-application list row. Unlike {@link CashApplicationResponse} (the webhook REST
 * response), allocations are structured — {@code invoiceId} + label — so the template can link
 * each allocation to its invoice detail. {@code debtorName} comes from the first allocation's
 * invoice; null (rendered as a dash) for UNAPPLIED/parked payments with no allocation.
 */
public record CashApplicationListItem(
        String paymentReference,
        CashApplicationStatus status,
        BigDecimal amount,
        Instant receivedAt,
        String vaNumber,
        String debtorName,
        List<Allocation> allocations
) {
    public record Allocation(String invoiceId, String label) {
    }

    public static CashApplicationListItem from(CashApplication c) {
        List<Allocation> allocations = c.getLines() == null ? List.of()
                : c.getLines().stream().map(CashApplicationListItem::allocation).toList();
        return new CashApplicationListItem(
                c.getPaymentReference(), c.getStatus(), c.getAmount(), c.getReceivedAt(),
                c.getCharge() != null ? c.getCharge().getVaNumber() : null,
                allocations.isEmpty() ? null : debtorName(c.getLines().get(0)),
                allocations);
    }

    private static Allocation allocation(CashApplicationLine line) {
        if (line.getInvoice() != null) {
            return new Allocation(line.getInvoice().getId(), line.getInvoice().getInvoiceNumber());
        }
        Installment installment = line.getInstallment();
        Invoice invoice = installment.getSchedule().getInvoice();
        return new Allocation(invoice.getId(),
                invoice.getInvoiceNumber()
                        + " · cicilan " + installment.getSequence()
                        + "/" + installment.getSchedule().getInstallmentCount());
    }

    private static String debtorName(CashApplicationLine line) {
        Invoice invoice = line.getInvoice() != null
                ? line.getInvoice()
                : line.getInstallment().getSchedule().getInvoice();
        return invoice.getDebtor().getName();
    }
}
