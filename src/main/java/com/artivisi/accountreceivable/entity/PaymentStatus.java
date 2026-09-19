package com.artivisi.accountreceivable.entity;

/**
 * Receivable lifecycle. Overdue is intentionally absent — it is derived
 * ({@code dueDate < today AND outstanding > 0}), not a stored state, because an invoice can be
 * partially paid and overdue at once.
 */
public enum PaymentStatus {
    OPEN,
    PARTIALLY_PAID,
    PAID,
    WRITTEN_OFF,
    /**
     * Deactivated in the originating system without being collected.
     *
     * <p>Distinct from {@link #WRITTEN_OFF}, which is a deliberate accounting decision to stop
     * pursuing a real debt. A cancelled invoice was withdrawn — most often reissued under a new
     * number after a correction — so counting it as a write-off would overstate credit losses
     * enormously: in a real migration, cancelled bills added up to most of what had ever been
     * collected.
     *
     * <p>The migrated source records only "deactivated", with no link from the old bill to its
     * replacement, so genuine cancellations and reissues cannot be told apart here. This status
     * therefore means exactly what the data supports and no more.
     */
    CANCELLED
}
