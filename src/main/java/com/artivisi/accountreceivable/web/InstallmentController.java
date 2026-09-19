package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.InvoiceResponse;
import com.artivisi.accountreceivable.dto.PaymentRequest;
import com.artivisi.accountreceivable.service.InvoiceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/installments")
public class InstallmentController {

    private final InvoiceService service;

    public InstallmentController(InvoiceService service) {
        this.service = service;
    }

    /** Phase-1 internal receipt against an installment; returns the parent invoice view. */
    @PostMapping("/{id}/payments")
    public InvoiceResponse pay(@PathVariable String id, @Valid @RequestBody PaymentRequest request) {
        return service.applyInstallmentPayment(id, request.amount());
    }
}
