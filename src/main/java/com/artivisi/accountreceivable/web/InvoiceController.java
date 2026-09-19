package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.InvoiceResponse;
import com.artivisi.accountreceivable.dto.InvoiceSummaryResponse;
import com.artivisi.accountreceivable.dto.IssueInvoiceRequest;
import com.artivisi.accountreceivable.dto.PaymentRequest;
import com.artivisi.accountreceivable.service.InvoiceService;
import com.artivisi.accountreceivable.service.ReceivableReviewService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {

    private final InvoiceService service;
    private final ReceivableReviewService reviewService;

    public InvoiceController(InvoiceService service, ReceivableReviewService reviewService) {
        this.service = service;
        this.reviewService = reviewService;
    }

    @PostMapping
    public ResponseEntity<InvoiceResponse> issue(@Valid @RequestBody IssueInvoiceRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.issue(request));
    }

    @GetMapping("/{id}")
    public InvoiceResponse get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Paginated invoice search — filtered, sorted, and limited in the database (never a full-table
     * load). Optional {@code status} (a PaymentStatus name or {@code MENUNGGAK} for overdue),
     * {@code type}, {@code q} (invoice number / debtor code). Paging via {@code page}/{@code size}/
     * {@code sort}; default 20 rows, newest first. Returns lightweight summaries — fetch lines /
     * installments via {@code GET /api/invoices/{id}}.
     */
    @GetMapping
    public PagedModel<InvoiceSummaryResponse> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "invoiceNumber", direction = Sort.Direction.DESC) Pageable pageable) {
        return new PagedModel<>(service.searchSummaryPage(status, type, q, pageable));
    }

    /** Phase-1 internal receipt; superseded by idempotent cash application in phase 2. */
    @PostMapping("/{id}/payments")
    public InvoiceResponse pay(@PathVariable String id, @Valid @RequestBody PaymentRequest request) {
        return service.applyInvoicePayment(id, request.amount());
    }

    @PostMapping("/{id}/write-off")
    public InvoiceResponse writeOff(@PathVariable String id, @RequestParam String reason) {
        return service.writeOff(id, reason);
    }

    /**
     * Cancel an invoice: it is treated as never having been a debt, unlike a write-off. The same
     * operation the {@code invoice.cancelled} command performs, for operators and repair scripts.
     */
    @PostMapping("/{id}/cancel")
    public InvoiceResponse cancel(@PathVariable String id,
                                  @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
                                  com.artivisi.accountreceivable.dto.CancelInvoiceRequest request) {
        service.cancel(id, request.reason(), request.replacedBy(), request.note(), "api");
        return service.get(id);
    }

    /**
     * The originating billing system reports this bill retired. Records the fact and queues it for
     * review; deliberately does NOT write the receivable off, because the rail reporting a
     * retirement is not always right and forgiving a debt is a person's call.
     *
     * <p>Exists so a mirror that cannot safely write off — the invoice is already settled, or
     * cancelled — has somewhere to put the signal other than its own failure log, where nobody
     * sees it.
     */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<Void> withdraw(@PathVariable String id, @RequestParam String reason) {
        reviewService.withdraw(id, reason);
        return ResponseEntity.noContent().build();
    }
}
