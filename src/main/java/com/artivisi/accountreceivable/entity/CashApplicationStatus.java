package com.artivisi.accountreceivable.entity;

/**
 * Outcome of applying a received payment. UNAPPLIED = parked for review (ambiguous target or
 * over-payment); the money is recorded but never silently allocated.
 */
public enum CashApplicationStatus {
    UNAPPLIED,
    PARTIALLY_APPLIED,
    APPLIED,
    REVERSED
}
