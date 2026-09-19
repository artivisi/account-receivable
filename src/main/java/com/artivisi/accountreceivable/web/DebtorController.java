package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.DebtorRequest;
import com.artivisi.accountreceivable.dto.DebtorResponse;
import com.artivisi.accountreceivable.service.DebtorService;
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
@RequestMapping("/api/debtors")
public class DebtorController {

    private final DebtorService service;

    public DebtorController(DebtorService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<DebtorResponse> create(@Valid @RequestBody DebtorRequest request) {
        DebtorResponse body = DebtorResponse.from(service.create(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }

    @GetMapping("/{id}")
    public DebtorResponse get(@PathVariable String id) {
        return DebtorResponse.from(service.get(id));
    }

    /**
     * Paginated debtor search — filtered, sorted, and limited in the database (never a full-table
     * load, since debtors can grow large). Optional {@code q} matches debtor code or name. Paging via
     * {@code page}/{@code size}/{@code sort}; default 20 rows, by code.
     */
    @GetMapping
    public PagedModel<DebtorResponse> list(
            @RequestParam(required = false) String q,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(service.list(q, pageable).map(DebtorResponse::from));
    }
}
