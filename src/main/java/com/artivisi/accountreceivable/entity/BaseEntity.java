package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;

/**
 * Shared identity + timestamps for all persistent entities. UUID string id, server-managed
 * created/updated instants. The {@code created_by}/{@code updated_by} audit columns are added
 * once authentication exists (no placeholder user is invented before then).
 */
@Getter
@Setter
@MappedSuperclass
public abstract class BaseEntity {

    @Id
    @UuidGenerator
    private String id;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;
}
