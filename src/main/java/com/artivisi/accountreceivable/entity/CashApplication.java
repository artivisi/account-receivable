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
 * A received payment. Idempotent on {@code (source, paymentReference)} — a replayed webhook
 * resolves to the same row. Allocation lines are added only when the payment is applied.
 */
@Getter
@Setter
@Entity
@Table(name = "cash_application")
public class CashApplication extends BaseEntity {

    private String paymentReference;

    /**
     * Which namespace {@link #paymentReference} belongs to. Part of the row's identity: the unique
     * constraint is (source, payment_reference), never the reference alone.
     */
    @Enumerated(EnumType.STRING)
    private PaymentSource source;

    /**
     * How the money arrived, for a {@link PaymentSource#RECORDED} payment. Null for a gateway
     * payment, where the channel is the VA and the question does not arise.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "payment_channel")
    private PaymentChannel paymentChannel;

    /**
     * The charge this payment settled, where one did. Null for a payment that never passed through
     * a VA: a {@link PaymentSource#RECORDED} receipt from a counter, and a gateway finding booked
     * against an invoice whose charge was already spent.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_charge")
    private Charge charge;

    private BigDecimal amount;

    private String currency;

    private Instant receivedAt;

    @Enumerated(EnumType.STRING)
    private CashApplicationStatus status;

    private String note;

    /** When this payment was reversed, and on what grounds. Null until it is. */
    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversal_reason")
    private String reversalReason;

    /**
     * The approval the reversal rests on, when a person decided it upstream. Same meaning as
     * {@code reference} on the v2 commands: it points at a record in the deciding application, so a
     * reversal in these books can be traced back to who allowed it.
     */
    @Column(name = "reversal_reference")
    private String reversalReference;

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
