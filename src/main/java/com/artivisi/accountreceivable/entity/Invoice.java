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
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The receivable. {@code amount} is the sum of its lines and is immutable once issued — corrections
 * are credit notes / write-off, never edits. {@code outstanding} is reduced by cash application.
 */
@Getter
@Setter
@Entity
@Table(name = "invoice")
public class Invoice extends BaseEntity {

    private String invoiceNumber;

    /** Bill number in an originating system (e.g. legacy nomorTagihan); null for AR-native issues. */
    private String sourceBillNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_debtor")
    private Debtor debtor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_invoice_type")
    private InvoiceType invoiceType;

    private LocalDate issueDate;

    private LocalDate dueDate;

    private String currency;

    private BigDecimal amount;

    private BigDecimal outstanding;

    @Enumerated(EnumType.STRING)
    private PaymentStatus paymentStatus;

    @Column(name = "is_installment")
    private boolean installment;

    /**
     * Denormalized earliest still-unpaid due date (single-payment: own due date; installment: the
     * earliest unpaid installment's; NULL when fully paid or written off). Maintained by
     * {@link #recomputeEarliestUnpaidDueDate()} so overdue is a pure SQL predicate — see V3 migration.
     */
    @Column(name = "earliest_unpaid_due_date")
    private LocalDate earliestUnpaidDueDate;

    private String description;

    /**
     * When the originating billing system reported this receivable retired, and why. Never changes
     * {@link #paymentStatus} — retirement upstream is evidence for a review, not a decision to
     * forgive. See the V8 migration for why the two are kept apart.
     */
    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawn_reason")
    private String withdrawnReason;

    /**
     * When a reviewer decided this receivable is still worth collecting, and their note. Set means
     * "looked at, keep collecting" — the queue skips it, so deliberate keeps stop reappearing beside
     * rows nobody has read. Cleared whenever a fresh retirement arrives, since that is new evidence.
     */
    @Column(name = "review_kept_at")
    private Instant reviewKeptAt;

    @Column(name = "review_note")
    private String reviewNote;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo asc")
    private List<InvoiceLine> lines = new ArrayList<>();

    @OneToOne(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private PaymentSchedule schedule;

    public void addLine(InvoiceLine line) {
        line.setInvoice(this);
        lines.add(line);
    }

    /**
     * Whether this invoice still represents money we expect to collect. Both terminal non-payment
     * states are excluded: {@code WRITTEN_OFF} (we decided to stop pursuing it) and {@code CANCELLED}
     * (it was withdrawn, usually reissued under a new number). Anything that answers "is this a live
     * receivable?" must go through here rather than testing a single status, which is how CANCELLED
     * would otherwise leak into aging, dunning and overdue maths.
     */
    public boolean isCollectible() {
        return paymentStatus != PaymentStatus.WRITTEN_OFF && paymentStatus != PaymentStatus.CANCELLED;
    }

    /**
     * Recompute {@link #earliestUnpaidDueDate} after any change to outstanding / payment status /
     * installments. Call at every such mutation so the denormalized column — and thus the SQL
     * overdue predicate ({@code earliest_unpaid_due_date < today}) — stays correct.
     */
    public void recomputeEarliestUnpaidDueDate() {
        if (!isCollectible() || outstanding == null || outstanding.signum() == 0) {
            this.earliestUnpaidDueDate = null;
            return;
        }
        if (installment && schedule != null) {
            this.earliestUnpaidDueDate = schedule.getInstallments().stream()
                    .filter(i -> i.getPaymentStatus() != PaymentStatus.WRITTEN_OFF
                            && i.getPaymentStatus() != PaymentStatus.CANCELLED
                            && i.getOutstanding().signum() > 0)
                    .map(Installment::getDueDate)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
        } else {
            this.earliestUnpaidDueDate = dueDate;
        }
    }
}
