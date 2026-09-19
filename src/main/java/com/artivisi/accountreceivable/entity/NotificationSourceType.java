package com.artivisi.accountreceivable.entity;

/** What AR event produced a notification outbox row (for traceability, not routing). */
public enum NotificationSourceType {
    INVOICE_ISSUED,
    PAYMENT_RECEIVED,
    DUNNING
}
