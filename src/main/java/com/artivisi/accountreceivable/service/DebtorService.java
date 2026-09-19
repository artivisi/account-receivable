package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.service.contact.PhoneNumbers;
import com.artivisi.accountreceivable.dto.DebtorRequest;
import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.exception.DuplicateException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.DebtorRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DebtorService {

    private final DebtorRepository repository;
    private final AuditService auditService;

    public DebtorService(DebtorRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    @Transactional
    public Debtor create(DebtorRequest request) {
        if (repository.existsByCode(request.code())) {
            throw new DuplicateException("Debtor code already exists: " + request.code());
        }
        Debtor d = new Debtor();
        d.setCode(request.code());
        d.setName(request.name());
        d.setEmail(request.email());
        d.setPhone(PhoneNumbers.normalise(request.phone()));
        d.setStatus(request.status());
        Debtor saved = repository.save(d);
        auditService.record("DEBTOR_CREATED", "Debtor", saved.getId(), "code=" + saved.getCode());
        return saved;
    }

    @Transactional
    public Debtor update(String id, DebtorRequest request) {
        Debtor d = get(id);
        d.setName(request.name());
        d.setEmail(request.email());
        d.setPhone(PhoneNumbers.normalise(request.phone()));
        d.setStatus(request.status());
        return d;
    }

    @Transactional(readOnly = true)
    public Debtor get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new NotFoundException("Debtor not found: " + id));
    }

    @Transactional(readOnly = true)
    public Page<Debtor> list(String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return repository.findAll(pageable);
        }
        return repository.findByCodeContainingIgnoreCaseOrNameContainingIgnoreCase(q, q, pageable);
    }
}
