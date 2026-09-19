package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.InvoiceTypeVaCodeRequest;
import com.artivisi.accountreceivable.entity.InvoiceType;
import com.artivisi.accountreceivable.entity.InvoiceTypeVaCode;
import com.artivisi.accountreceivable.exception.DuplicateException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.InvoiceTypeRepository;
import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class InvoiceTypeVaCodeService {

    private final InvoiceTypeVaCodeRepository repository;
    private final InvoiceTypeRepository invoiceTypeRepository;
    private final AuditService auditService;

    public InvoiceTypeVaCodeService(InvoiceTypeVaCodeRepository repository,
                                    InvoiceTypeRepository invoiceTypeRepository,
                                    AuditService auditService) {
        this.repository = repository;
        this.invoiceTypeRepository = invoiceTypeRepository;
        this.auditService = auditService;
    }

    @Transactional
    public InvoiceTypeVaCode set(String invoiceTypeId, InvoiceTypeVaCodeRequest request) {
        InvoiceType invoiceType = invoiceTypeRepository.findById(invoiceTypeId)
                .orElseThrow(() -> new NotFoundException("Invoice type not found: " + invoiceTypeId));

        if (repository.existsByVaCodeAndInvoiceTypeIdNot(request.vaCode(), invoiceTypeId)) {
            throw new DuplicateException("VA code " + request.vaCode() + " is already assigned to another invoice type");
        }

        InvoiceTypeVaCode mapping = repository.findByInvoiceTypeId(invoiceTypeId)
                .orElseGet(InvoiceTypeVaCode::new);
        mapping.setInvoiceType(invoiceType);
        mapping.setVaCode(request.vaCode());
        InvoiceTypeVaCode saved = repository.save(mapping);
        auditService.record("INVOICE_TYPE_VA_CODE_SET", "InvoiceTypeVaCode", saved.getId(),
                "invoiceType=" + invoiceType.getCode() + " vaCode=" + request.vaCode());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<InvoiceTypeVaCode> findByInvoiceTypeId(String invoiceTypeId) {
        return repository.findByInvoiceTypeId(invoiceTypeId);
    }

    @Transactional(readOnly = true)
    public List<InvoiceTypeVaCode> list() {
        return repository.findAll();
    }
}
