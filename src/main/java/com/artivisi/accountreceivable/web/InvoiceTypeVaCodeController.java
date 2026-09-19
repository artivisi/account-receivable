package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.InvoiceTypeVaCodeRequest;
import com.artivisi.accountreceivable.dto.InvoiceTypeVaCodeResponse;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.service.InvoiceTypeVaCodeService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class InvoiceTypeVaCodeController {

    private final InvoiceTypeVaCodeService service;

    public InvoiceTypeVaCodeController(InvoiceTypeVaCodeService service) {
        this.service = service;
    }

    @PutMapping("/invoice-types/{id}/va-code")
    public InvoiceTypeVaCodeResponse set(@PathVariable String id,
                                         @Valid @RequestBody InvoiceTypeVaCodeRequest request) {
        return InvoiceTypeVaCodeResponse.from(service.set(id, request));
    }

    @GetMapping("/invoice-types/{id}/va-code")
    public InvoiceTypeVaCodeResponse get(@PathVariable String id) {
        return service.findByInvoiceTypeId(id)
                .map(InvoiceTypeVaCodeResponse::from)
                .orElseThrow(() -> new NotFoundException("No VA code mapping for invoice type: " + id));
    }

    @GetMapping("/invoice-type-va-codes")
    public List<InvoiceTypeVaCodeResponse> list() {
        return service.list().stream().map(InvoiceTypeVaCodeResponse::from).toList();
    }
}
