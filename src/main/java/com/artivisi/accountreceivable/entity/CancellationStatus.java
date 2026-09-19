package com.artivisi.accountreceivable.entity;

/** Outbound charge-cancellation outbox lifecycle. FAILED is terminal (surfaced, never dropped). */
public enum CancellationStatus {
    PENDING,
    DONE,
    FAILED
}
