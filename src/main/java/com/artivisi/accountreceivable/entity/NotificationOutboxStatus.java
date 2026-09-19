package com.artivisi.accountreceivable.entity;

/** Notification outbox lifecycle. SENT (published to the hub) and FAILED are both terminal. */
public enum NotificationOutboxStatus {
    PENDING,
    SENT,
    FAILED
}
