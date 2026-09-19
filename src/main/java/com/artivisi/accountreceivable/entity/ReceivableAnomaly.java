package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Something wrong with a receivable that AR could not have worked out by itself.
 *
 * <p>The review queue derives what current state can prove — a charge superseded on a reused VA, a
 * sibling bill already paid, a bill the source system withdrew — and recomputes it on every read.
 * Those never belong here: a stored copy of a derived fact is a fact that will eventually be wrong.
 * This holds the other kind, where something outside AR looked and reported back: a bank report that
 * omits a payment we hold, a reconciliation run that found money notified but never settled, a
 * divergence an operator spotted across two systems.
 *
 * <p>A row is an observation awaiting a person. Nothing here changes a status, a balance, or a
 * charge — raising and deciding are deliberately separate.
 */
@Getter
@Setter
@Entity
@Table(name = "receivable_anomaly")
public class ReceivableAnomaly extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "id_invoice")
    private Invoice invoice;

    /** Set when the finding is about one charge rather than the receivable as a whole. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_charge")
    private Charge charge;

    /**
     * Not an enum yet, on purpose: the stable set is meant to be found by curating what is actually
     * on the books rather than decided in advance. See V12 for when to promote it.
     */
    private String category;

    /** RECON, BRIDGE, OPS, FINANCE — how much the resolver should trust the assertion. */
    private String source;

    private String detail;

    /** The identifier a reviewer can chase in the system that raised it; also the idempotency key. */
    private String evidenceRef;

    /**
     * What the bank recorded, for a finding whose remedy is booking the payment (see V14). Booking
     * reads these instead of asking an operator to re-type money. Null when the finding has no
     * payment behind it or predates the columns — such a finding is not bookable.
     */
    private java.math.BigDecimal evidenceAmount;

    private Instant evidenceAt;

    private String evidenceVaNumber;

    private String evidenceBank;

    private String raisedBy;

    private Instant resolvedAt;

    private String resolvedBy;

    private String resolution;

    private String resolutionNote;

    /** Open means nobody has decided yet. The queue reads these. */
    public boolean open() {
        return resolvedAt == null;
    }
}
