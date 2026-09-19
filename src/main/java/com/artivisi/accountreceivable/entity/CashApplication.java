package com.artivisi.accountreceivable.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A received payment. Idempotent on {@code gatewayPaymentReference} (unique) — a replayed webhook
 * resolves to the same row. Allocation lines are added only when the payment is applied.
 */
@Getter
@Setter
@Entity
@Table(name = "cash_application")
public class CashApplication extends BaseEntity {

    private String gatewayPaymentReference;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_charge")
    private Charge charge;

    private BigDecimal amount;

    private String currency;

    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    private CashApplicationStatus status;

    private String note;

    /**
     * When an external check found that the originating billing system had not booked this payment,
     * and what it saw. Nothing in this ledger implies it — the receivable is paid and the cash is
     * applied — so the fact can only be recorded from outside. See the V9 migration.
     */
    @Column(name = "upstream_missing_at")
    private Instant upstreamMissingAt;

    @Column(name = "upstream_missing_note")
    private String upstreamMissingNote;

    /** When a person confirmed it had been dealt with upstream, and how. Cleared, never deleted. */
    @Column(name = "upstream_cleared_at")
    private Instant upstreamClearedAt;

    @Column(name = "upstream_cleared_note")
    private String upstreamClearedNote;

    @OneToMany(mappedBy = "cashApplication", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CashApplicationLine> lines = new ArrayList<>();

    public void addLine(CashApplicationLine line) {
        line.setCashApplication(this);
        lines.add(line);
    }
}
