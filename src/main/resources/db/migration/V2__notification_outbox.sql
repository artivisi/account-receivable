-- Outbound outbox for hub notifications (bill+VA issued, payment received, overdue dunning).
-- Enqueued in the business transaction; a dispatcher publishes to the notification hub's Kafka
-- topic with retry so a notification is never lost between commit and publish. AR's reliability
-- guarantee ends at "published to the topic" (the hub does not report delivery back).
create table notification_outbox (
    id               varchar(36) primary key,
    config_id        varchar(64)  not null,
    recipient_email  varchar(255),
    recipient_mobile varchar(32),
    data             jsonb        not null,
    source_type      varchar(20)  not null,   -- INVOICE_ISSUED | PAYMENT_RECEIVED | DUNNING
    source_id        varchar(36)  not null,
    status           varchar(20)  not null,   -- PENDING | SENT | FAILED
    attempts         integer      not null,
    max_attempts     integer      not null,
    next_attempt_at  timestamptz  not null,
    last_error       varchar(512),
    created_at       timestamptz  not null,
    updated_at       timestamptz  not null
);
create index idx_notification_outbox_due on notification_outbox (status, next_attempt_at);
