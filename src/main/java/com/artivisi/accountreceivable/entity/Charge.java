package com.artivisi.accountreceivable.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Local mirror of a gateway charge opened to collect a receivable. Targets EITHER an invoice
 * (single-payment) OR an installment. {@code consumerReference} (= the target id) is the gateway
 * idempotency key.
 */
@Getter
@Setter
@Entity
@Table(name = "charge")
public class Charge extends BaseEntity {

    private String gatewayChargeId;

    private String consumerReference;

    @Enumerated(EnumType.STRING)
    private ChargeType chargeType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_invoice")
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_installment")
    private Installment installment;

    private BigDecimal amount;

    private String currency;

    @Enumerated(EnumType.STRING)
    private ChargeStatus status;

    private BigDecimal cumulativePaid;

    private String escrowCode;

    private String vaNumber;

    private Instant expiresAt;

    /**
     * When this charge stopped being collectible. NULL for every migrated row, and for anything
     * cancelled before the column existed — "we do not know", never "it was not cancelled".
     *
     * <p>The review queue keys off this rather than {@code updated_at}, which the bulk history import
     * made meaningless as a timing signal.
     */
    private Instant cancelledAt;

    /**
     * Retire this charge. Use this rather than setting the status directly: three separate paths
     * cancel a charge (a superseding bill takes its VA number, a write-off cancellation dispatches,
     * or the gateway tells us it cancelled one), and each one that forgot to stamp the moment would
     * put a row into the queue's blind spot.
     */
    public void cancel(Instant at) {
        this.status = ChargeStatus.CANCELLED;
        this.cancelledAt = at;
    }

    /**
     * When a newer bill took this charge's VA number. NULL for every other cancellation, and for
     * anything superseded before the column existed — "we do not know", never "it was not superseded".
     *
     * <p>Separate from {@link #cancelledAt} because only this reason may be undone without asking a
     * human: the debt was never in question, we retired its collection instrument for our own
     * bookkeeping. A write-off means the debt is forgiven; a cancellation the gateway reports means
     * something happened that we did not initiate. Neither may be reopened automatically.
     */
    private Instant supersededAt;

    /**
     * Retire this charge because a newer bill needs its VA number. Records both the cancellation and
     * its reason, so the number coming free again can put this receivable back into collection.
     */
    public void supersede(Instant at) {
        cancel(at);
        this.supersededAt = at;
    }
}
