package com.artivisi.accountreceivable.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Issue a receivable. The invoice {@code amount} is computed from the lines (not supplied), and for
 * an installment invoice the installments must sum to that amount. {@code installments} null/empty
 * → single-payment invoice.
 */
public record IssueInvoiceRequest(
        @NotBlank String debtorCode,
        @NotBlank String invoiceTypeCode,
        @NotNull LocalDate issueDate,
        @NotNull LocalDate dueDate,
        String description,
        @NotEmpty @Valid List<LineRequest> lines,
        @Valid List<InstallmentRequest> installments,
        /**
         * The bill's number in an originating system, when the invoice is mirrored from one (e.g. a
         * legacy billing application, via a bridge). Becomes the gateway charge's {@code billNumber},
         * which a bank adapter may show the payer. Null for AR-native issues.
         */
        String sourceBillNumber,
        /**
         * The upstream's own key for this request, echoed as {@code correlationId} on the
         * {@code invoice.issued} event so the sender can match answer to question. Null when the
         * invoice is issued from the admin UI or the REST API.
         */
        String correlationId
) {
    /** Back-compat overload for callers predating {@code sourceBillNumber} (defaults null). */
    public IssueInvoiceRequest(String debtorCode, String invoiceTypeCode, LocalDate issueDate, LocalDate dueDate,
                               String description, List<LineRequest> lines, List<InstallmentRequest> installments) {
        this(debtorCode, invoiceTypeCode, issueDate, dueDate, description, lines, installments, null, null);
    }

    /** Back-compat overload for callers predating {@code correlationId}. */
    public IssueInvoiceRequest(String debtorCode, String invoiceTypeCode, LocalDate issueDate, LocalDate dueDate,
                               String description, List<LineRequest> lines, List<InstallmentRequest> installments,
                               String sourceBillNumber) {
        this(debtorCode, invoiceTypeCode, issueDate, dueDate, description, lines, installments, sourceBillNumber, null);
    }

    public record LineRequest(
            @NotBlank String description,
            @NotNull @DecimalMin(value = "0.0001") BigDecimal quantity,
            @NotNull @DecimalMin(value = "0.00") BigDecimal unitAmount
    ) {
    }

    public record InstallmentRequest(
            @NotNull LocalDate dueDate,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount
    ) {
    }
}
