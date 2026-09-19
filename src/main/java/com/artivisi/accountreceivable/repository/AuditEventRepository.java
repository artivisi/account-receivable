package com.artivisi.accountreceivable.repository;

import com.artivisi.accountreceivable.entity.AuditEvent;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, String> {

    List<AuditEvent> findByOrderByCreatedAtDesc(Limit limit);

    List<AuditEvent> findByEntityTypeAndEntityIdOrderByCreatedAtDesc(String entityType, String entityId);

    Page<AuditEvent> findByActorContainingIgnoreCaseOrEventTypeContainingIgnoreCaseOrDetailContainingIgnoreCase(
            String actor, String eventType, String detail, Pageable p);
}
