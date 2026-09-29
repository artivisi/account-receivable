package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArInvoiceProperties;
import com.artivisi.accountreceivable.dto.CreditNoteRequest;
import com.artivisi.accountreceivable.dto.CreditNoteResponse;
import com.artivisi.accountreceivable.entity.CreditNote;
import com.artivisi.accountreceivable.entity.CreditReason;
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
 * reduces {@code outstanding} and stands beside the invoice as the reason it was not collected.
 *
 * <p>Issue it through {@code CollectionService.issueCreditNote} rather than here directly: a bill
 * that no longer owes anything must also stop being payable at the bank, and this service does not
 * reach the gateway.
 */
@Service
public class CreditNoteService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceService invoiceService;
    private final CreditNoteRepository creditNoteRepository;
    private final RunningNumberService runningNumberService;
    private final AuditService auditService;
    private final ArInvoiceProperties invoiceProperties;
    private final Clock clock;

    public CreditNoteService(InvoiceRepository invoiceRepository,
                             InvoiceService invoiceService,
                             CreditNoteRepository creditNoteRepository,
                             RunningNumberService runningNumberService,
                             AuditService auditService,
                             ArInvoiceProperties invoiceProperties,
                             Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.invoiceService = invoiceService;
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
        if (!invoice.isCollectible()) {
            throw new InvalidRequestException("INVOICE_NOT_AMENDABLE", "Cannot credit invoice "
                    + invoice.getInvoiceNumber() + " in status " + invoice.getPaymentStatus());
        }
        if (request.reasonCode() == CreditReason.SCHOLARSHIP && isBlank(request.reference())) {
            throw new InvalidRequestException("REFERENCE_REQUIRED", "A scholarship credit needs the"
                    + " decision it rests on in reference (e.g. the decree number)");
        }
        if (invoice.getPaymentStatus() == PaymentStatus.WRITTEN_OFF) {
            throw new InvalidRequestException("INVOICE_NOT_AMENDABLE",
                    "Cannot credit a written-off invoice");
        }
        BigDecimal amount = exactMoney(request.amount());
        if (amount.compareTo(invoice.getOutstanding()) > 0) {
            throw new InvalidRequestException("AMOUNT_INVALID",
                    "Credit note " + amount + " exceeds outstanding " + invoice.getOutstanding());
        }

        invoiceService.applyCredit(invoice, amount);

        CreditNote creditNote = new CreditNote();
        creditNote.setCreditNoteNumber(runningNumberService.next(
                invoiceProperties.creditNotePrefix(), invoiceProperties.numberPadLength()));
        creditNote.setInvoice(invoice);
        creditNote.setDebtor(invoice.getDebtor());
        creditNote.setIssueDate(LocalDate.now(clock));
        creditNote.setCurrency(invoice.getCurrency());
        creditNote.setAmount(amount);
        creditNote.setReasonCode(request.reasonCode());
        creditNote.setReference(request.reference());
        creditNote.setReason(request.reason());
        CreditNote saved = creditNoteRepository.save(creditNote);

        auditService.record("CREDIT_NOTE_ISSUED", "Invoice", invoice.getId(),
                saved.getCreditNoteNumber() + " amount=" + amount + " kind=" + request.reasonCode()
                        + (isBlank(request.reference()) ? "" : " reference=" + request.reference()));

        return CreditNoteResponse.from(saved);
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }

    private static BigDecimal exactMoney(BigDecimal value) {
        if (value.stripTrailingZeros().scale() > 2) {
            throw new InvalidRequestException("AMOUNT_INVALID", "amount has sub-cent precision: " + value);
        }
        return value.setScale(2);
    }
}
