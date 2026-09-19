package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.InvoiceTypeRequest;
import com.artivisi.accountreceivable.entity.InvoiceType;
import com.artivisi.accountreceivable.exception.DuplicateException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.InvoiceTypeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InvoiceTypeService {

    private final InvoiceTypeRepository repository;
    private final AuditService auditService;

    public InvoiceTypeService(InvoiceTypeRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public InvoiceType create(InvoiceTypeRequest request) {
        if (repository.existsByCode(request.code())) {
            throw new DuplicateException("Invoice type code already exists: " + request.code());
        }
        InvoiceType t = new InvoiceType();
        t.setCode(request.code());
        t.setName(request.name());
        t.setActive(request.active());
        InvoiceType saved = repository.save(t);
        auditService.record("INVOICE_TYPE_CREATED", "InvoiceType", saved.getId(), "code=" + saved.getCode());
        return saved;
    }

    @Transactional
    public InvoiceType update(String id, InvoiceTypeRequest request) {
        InvoiceType t = get(id);
        t.setName(request.name());
        t.setActive(request.active());
        return t;
    }

    @Transactional(readOnly = true)
    public InvoiceType get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice type not found: " + id));
    }

    /** Full list — for the bounded admin types page and the invoice-list filter dropdown. */
    @Transactional(readOnly = true)
    public List<InvoiceType> list() {
        return repository.findAll();
    }

    /** Paginated search. {@code activeOnly} restricts to selectable types (the invoice-form picker). */
    @Transactional(readOnly = true)
    public Page<InvoiceType> search(String q, boolean activeOnly, Pageable pageable) {
        String qFilter = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        return repository.search(activeOnly, qFilter, pageable);
    }
}
