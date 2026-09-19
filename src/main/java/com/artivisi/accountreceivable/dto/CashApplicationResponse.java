package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.CashApplicationLine;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import com.artivisi.accountreceivable.entity.Installment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Collectors;

public record CashApplicationResponse(
        String gatewayPaymentReference,
        CashApplicationStatus status,
        BigDecimal amount,
        String note,
        Instant receivedAt,
        String vaNumber,
        String allocationDescription
) {
    public static CashApplicationResponse from(CashApplication c) {
        return new CashApplicationResponse(
                c.getGatewayPaymentReference(), c.getStatus(), c.getAmount(), c.getNote(),
                c.getReceivedAt(),
                c.getCharge() != null ? c.getCharge().getVaNumber() : null,
                allocationDescription(c));
    }

    private static String allocationDescription(CashApplication c) {
        if (c.getLines() == null || c.getLines().isEmpty()) {
            return null;
        }
        return c.getLines().stream()
                .map(CashApplicationResponse::describeLine)
                .collect(Collectors.joining(", "));
    }

    private static String describeLine(CashApplicationLine line) {
        if (line.getInvoice() != null) {
            return line.getInvoice().getInvoiceNumber();
        }
        Installment installment = line.getInstallment();
        return installment.getSchedule().getInvoice().getInvoiceNumber()
                + " · cicilan " + installment.getSequence()
                + "/" + installment.getSchedule().getInstallmentCount();
    }
}
