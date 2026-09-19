package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * An event waiting to be published on the v2 contract. Written in the same transaction as the
 * change it announces, so an event cannot be lost between commit and broker and cannot describe a
 * change that rolled back. {@code payload} is the complete message, envelope included.
 */
@Getter
@Setter
@Entity
@Table(name = "contract_event_outbox")
public class ContractEventOutbox extends BaseEntity {

    private String topic;
    private String messageKey;
    private String eventType;
    private String payload;

    @Enumerated(EnumType.STRING)
    private ContractOutboxStatus status;

    private int attempts;
    private int maxAttempts;
    private Instant nextAttemptAt;
    private String lastError;
}
