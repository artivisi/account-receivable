package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.entity.AuditEvent;
import com.artivisi.accountreceivable.repository.AuditEventRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records who-did-what. Joins the caller's transaction so the audit row commits/rolls back with the
 * audited action. The actor is the authenticated UI user, or "system" for API/webhook/scheduled work.
 */
@Service
public class AuditService {

    private static final int RECENT = 200;

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(String eventType, String entityType, String entityId, String detail) {
        AuditEvent event = new AuditEvent();
        event.setEventType(eventType);
        event.setEntityType(entityType);
        event.setEntityId(entityId);
        event.setActor(currentActor());
        event.setDetail(detail);
        repository.save(event);
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> recent() {
        return repository.findByOrderByCreatedAtDesc(Limit.of(RECENT));
    }

    @Transactional(readOnly = true)
    public Page<AuditEvent> page(String q, Pageable pageable) {
        if (q == null || q.isBlank()) {
            return repository.findAll(pageable);
        }
        return repository.findByActorContainingIgnoreCaseOrEventTypeContainingIgnoreCaseOrDetailContainingIgnoreCase(
                q, q, q, pageable);
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())
                ? auth.getName() : "system";
    }
}
