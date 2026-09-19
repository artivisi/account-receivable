package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArInvoiceProperties;
import com.artivisi.accountreceivable.dto.CreditNoteRequest;
import com.artivisi.accountreceivable.dto.CreditNoteResponse;
import com.artivisi.accountreceivable.entity.CreditNote;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.CreditNoteRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

/**
 * Issues credit notes against issued invoices. The invoice amount stays immutable; the credit note
 * reduces {@code outstanding} and posts a reversing journal (Dr Revenue · Cr A/R control).
 * v1: single-payment invoices only (installment allocation is out of scope).
 */
@Service
public class CreditNoteService {

    private final InvoiceRepository invoiceRepository;
    private final CreditNoteRepository creditNoteRepository;
    private final RunningNumberService runningNumberService;
    private final AuditService auditService;
    private final ArInvoiceProperties invoiceProperties;
    private final Clock clock;

    public CreditNoteService(InvoiceRepository invoiceRepository,
                             CreditNoteRepository creditNoteRepository,
                             RunningNumberService runningNumberService,
                             AuditService auditService,
                             ArInvoiceProperties invoiceProperties,
                             Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.creditNoteRepository = creditNoteRepository;
        this.runningNumberService = runningNumberService;
        this.auditService = auditService;
        this.invoiceProperties = invoiceProperties;
        this.clock = clock;
    }

    @Transactional
    public CreditNoteResponse issue(CreditNoteRequest request) {
        Invoice invoice = invoiceRepository.findById(request.invoiceId())
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + request.invoiceId()));
        if (invoice.isInstallment()) {
            throw new InvalidRequestException("Credit notes for installment invoices are not supported");
        }
        if (invoice.getPaymentStatus() == PaymentStatus.WRITTEN_OFF) {
            throw new InvalidRequestException("Cannot credit a written-off invoice");
        }
        BigDecimal amount = exactMoney(request.amount());
        if (amount.compareTo(invoice.getOutstanding()) > 0) {
            throw new InvalidRequestException(
                    "Credit note " + amount + " exceeds outstanding " + invoice.getOutstanding());
        }

        invoice.setOutstanding(invoice.getOutstanding().subtract(amount));
        invoice.setPaymentStatus(invoice.getOutstanding().signum() == 0
                ? PaymentStatus.PAID : PaymentStatus.PARTIALLY_PAID);
        invoice.recomputeEarliestUnpaidDueDate();

        CreditNote creditNote = new CreditNote();
        creditNote.setCreditNoteNumber(runningNumberService.next(
                invoiceProperties.creditNotePrefix(), invoiceProperties.numberPadLength()));
        creditNote.setInvoice(invoice);
        creditNote.setDebtor(invoice.getDebtor());
        creditNote.setIssueDate(LocalDate.now(clock));
        creditNote.setCurrency(invoice.getCurrency());
        creditNote.setAmount(amount);
        creditNote.setReason(request.reason());
        CreditNote saved = creditNoteRepository.save(creditNote);

        auditService.record("CREDIT_NOTE_ISSUED", "Invoice", invoice.getId(),
                saved.getCreditNoteNumber() + " amount=" + amount);

        return CreditNoteResponse.from(saved);
    }

    private static BigDecimal exactMoney(BigDecimal value) {
        if (value.stripTrailingZeros().scale() > 2) {
            throw new InvalidRequestException("amount has sub-cent precision: " + value);
        }
        return value.setScale(2);
    }
}
