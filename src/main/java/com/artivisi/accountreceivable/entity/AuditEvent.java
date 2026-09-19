package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/** Who-did-what record for a mutating action. */
@Getter
@Setter
@Entity
@Table(name = "audit_event")
public class AuditEvent extends BaseEntity {

    private String eventType;

    private String entityType;

    private String entityId;

    private String actor;

    private String detail;
}
