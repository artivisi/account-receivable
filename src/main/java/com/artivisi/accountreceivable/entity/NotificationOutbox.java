package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

/**
 * Outbox row for a hub notification, retried until SENT or terminal FAILED. AR owns the trigger +
 * variables; the deployment's notification hub owns templates/rendering/delivery. Reliability
 * ends at "published to the topic" — the hub does not report delivery back.
 */
@Getter
@Setter
@Entity
@Table(name = "notification_outbox")
public class NotificationOutbox extends BaseEntity {

    /** Hub config id selecting the template set. */
    private String configId;

    /** Present → email channel offered to the hub. Null when the debtor has no email. */
    private String recipientEmail;

    /** Present → SMS channel offered to the hub. Null when no phone or SMS is disabled. */
    private String recipientMobile;

    /** Mustache variables for the hub template (all String), stored as jsonb. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "data")
    private Map<String, String> data;

    @Enumerated(EnumType.STRING)
    private NotificationSourceType sourceType;

    /** Invoice / charge / reminder id that triggered this, for traceability. */
    private String sourceId;

    @Enumerated(EnumType.STRING)
    private NotificationOutboxStatus status;

    private int attempts;

    private int maxAttempts;

    private Instant nextAttemptAt;

    private String lastError;
}
