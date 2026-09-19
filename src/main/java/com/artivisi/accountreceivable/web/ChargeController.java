package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.ChargeResponse;
import com.artivisi.accountreceivable.service.CollectionService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Opens gateway charges to collect a receivable (one CLOSED charge per invoice or installment). */
@RestController
public class ChargeController {

    private final CollectionService service;

    public ChargeController(CollectionService service) {
        this.service = service;
    }

    @PostMapping("/api/invoices/{id}/charge")
    public ResponseEntity<ChargeResponse> openForInvoice(@PathVariable String id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.openChargeForInvoice(id));
    }

    @PostMapping("/api/invoices/{id}/plan")
    public com.artivisi.accountreceivable.dto.InvoiceResponse amendPlan(
            @PathVariable String id,
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.artivisi.accountreceivable.dto.AmendPlanRequest request) {
        return service.amendPlan(id, request.installments(), request.reason(), "api", null);
    }

    /**
     * Move an invoice's due date and carry it through to the open gateway charge, so a deadline
     * corrected upstream reaches the payer rather than stopping at our books.
     */
    @PostMapping("/api/invoices/{id}/due-date")
    public ResponseEntity<Void> amendDueDate(@PathVariable String id,
                                             @Valid @RequestBody AmendDueDateRequest request) {
        service.amendDueDate(id, request.dueDate());
        return ResponseEntity.noContent().build();
    }

    /**
     * Move one installment's due date. Separate from the invoice endpoint because a schedule carries
     * a deadline per installment — moving the invoice's own date would not say which one changed.
     */
    @PostMapping("/api/installments/{id}/due-date")
    public ResponseEntity<Void> amendInstallmentDueDate(@PathVariable String id,
                                                        @Valid @RequestBody AmendDueDateRequest request) {
        service.amendInstallmentDueDate(id, request.dueDate());
        return ResponseEntity.noContent().build();
    }

    public record AmendDueDateRequest(@NotNull java.time.LocalDate dueDate) {
    }
}
