package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.InvoiceTypeRequest;
import com.artivisi.accountreceivable.dto.InvoiceTypeResponse;
import com.artivisi.accountreceivable.service.InvoiceTypeService;
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
@RequestMapping("/api/invoice-types")
public class InvoiceTypeController {

    private final InvoiceTypeService service;

    public InvoiceTypeController(InvoiceTypeService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<InvoiceTypeResponse> create(@Valid @RequestBody InvoiceTypeRequest request) {
        InvoiceTypeResponse body = InvoiceTypeResponse.from(service.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public InvoiceTypeResponse get(@PathVariable String id) {
        return InvoiceTypeResponse.from(service.get(id));
    }

    /** Paginated type search (optional {@code q} on code/name; default 20 by code). */
    @GetMapping
    public PagedModel<InvoiceTypeResponse> list(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(service.search(q, false, pageable).map(InvoiceTypeResponse::from));
    }
}
