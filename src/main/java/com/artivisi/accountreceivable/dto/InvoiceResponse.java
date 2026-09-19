package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.Installment;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.InvoiceLine;
import com.artivisi.accountreceivable.entity.PaymentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record InvoiceResponse(
        String id,
        String invoiceNumber,
        String debtorCode,
        String invoiceTypeCode,
        LocalDate issueDate,
        LocalDate dueDate,
        String currency,
        BigDecimal amount,
        BigDecimal outstanding,
        PaymentStatus paymentStatus,
        boolean overdue,
        boolean installment,
        String description,
        Instant withdrawnAt,
        String withdrawnReason,
        List<LineResponse> lines,
        List<InstallmentResponse> installments,
        Instant createdAt,
        Instant updatedAt
) {

    /**
     * Whether collection-side actions (open charge, write-off, credit note) are still possible —
     * mirrors the service-side guards ({@code CollectionService.assertCollectible},
     * {@code InvoiceService.writeOff}, {@code CreditNoteService.issue}), so the UI only renders
     * actions the backend would accept.
     */
    public boolean collectible() {
        return paymentStatus == PaymentStatus.OPEN || paymentStatus == PaymentStatus.PARTIALLY_PAID;
    }

    public record LineResponse(
            int lineNo,
            String description,
            BigDecimal quantity,
            BigDecimal unitAmount,
            BigDecimal lineAmount
    ) {
        static LineResponse from(InvoiceLine l) {
            return new LineResponse(l.getLineNo(), l.getDescription(), l.getQuantity(),
                    l.getUnitAmount(), l.getLineAmount());
        }
    }

    public record InstallmentResponse(
            String id,
            int sequence,
            LocalDate dueDate,
            BigDecimal amount,
            BigDecimal outstanding,
            PaymentStatus paymentStatus,
            boolean overdue
    ) {
        /** Same rule as {@link InvoiceResponse#collectible()}, per installment. */
        public boolean collectible() {
            return paymentStatus == PaymentStatus.OPEN || paymentStatus == PaymentStatus.PARTIALLY_PAID;
        }

        static InstallmentResponse from(Installment i, LocalDate today) {
            boolean overdue = i.getPaymentStatus() != PaymentStatus.WRITTEN_OFF
                    && i.getPaymentStatus() != PaymentStatus.CANCELLED
                    && i.getOutstanding().signum() > 0
                    && i.getDueDate().isBefore(today);
            return new InstallmentResponse(i.getId(), i.getSequence(), i.getDueDate(), i.getAmount(),
                    i.getOutstanding(), i.getPaymentStatus(), overdue);
        }
    }

    public static InvoiceResponse from(Invoice inv, LocalDate today) {
        List<LineResponse> lines = inv.getLines().stream().map(LineResponse::from).toList();
        List<InstallmentResponse> installments = inv.getSchedule() == null
                ? List.of()
                : inv.getSchedule().getInstallments().stream()
                        .map(i -> InstallmentResponse.from(i, today)).toList();
        boolean overdue = overdue(inv, today, installments);
        return new InvoiceResponse(
                inv.getId(), inv.getInvoiceNumber(), inv.getDebtor().getCode(),
                inv.getInvoiceType().getCode(), inv.getIssueDate(), inv.getDueDate(),
                inv.getCurrency(), inv.getAmount(), inv.getOutstanding(), inv.getPaymentStatus(),
                overdue, inv.isInstallment(), inv.getDescription(),
                inv.getWithdrawnAt(), inv.getWithdrawnReason(), lines, installments,
                inv.getCreatedAt(), inv.getUpdatedAt());
    }

    private static boolean overdue(Invoice inv, LocalDate today, List<InstallmentResponse> installments) {
        if (inv.getPaymentStatus() == PaymentStatus.WRITTEN_OFF
                || inv.getPaymentStatus() == PaymentStatus.CANCELLED
                || inv.getOutstanding().signum() == 0) {
            return false;
        }
        if (inv.isInstallment()) {
            return installments.stream().anyMatch(InstallmentResponse::overdue);
        }
        return inv.getDueDate().isBefore(today);
    }
}
