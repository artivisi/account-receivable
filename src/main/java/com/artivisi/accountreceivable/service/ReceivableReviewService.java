package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.ReviewQueueItem;
import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.CashApplicationRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The collectibility review queue: receivables that can no longer be collected, and the decision a
 * person takes on each.
 *
 * <p>Two states feed it, and they are different questions. A <b>dead VA</b> means we can no longer
 * take the money. A <b>withdrawal upstream</b> means the originating system says the bill is gone.
 * Neither is a decision to forgive the debt — that is this queue's whole point. Before it existed
 * the first was discoverable only by diffing three databases and the second died in the mirror's
 * failure log, so receivables sat open against bills that had been retired, none of them visible
 * to the people who could act.
 *
 * <p><b>The evidence is deliberately not a conclusion.</b> Each row reports what the ledger can
 * prove and the reviewer supplies the judgement. The sharpest trap is here: a paid charge on the
 * same VA number looks like proof this debt was settled, and is not — numbers are reused by
 * successive bills, so the payment may belong to another period. On 2026-08-18 exactly that
 * reasoning would have written off a live monthly instalment whose paid "sibling" was the previous
 * month's. So the query surfaces the paid sibling for a human to check, and the verdict is named
 * {@code POSSIBLY_SETTLED}.
 */
@Service
public class ReceivableReviewService {

    /**
     * Charges reach an invoice by two routes — directly, or through one of its installments — and
     * every aggregate below needs both. Kept as one CTE so no query forgets the second branch: an
     * implicit join there would silently drop every installment receivable from the queue.
     */
    private static final String CHARGE_OF_INVOICE = """
            charge_of_invoice as (
              select coalesce(c.id_invoice, s.id_invoice) as id_invoice,
                     c.id, c.status, c.va_number, c.cancelled_at, c.expires_at
                from charge c
                left join installment i on i.id = c.id_installment
                left join payment_schedule s on s.id = i.id_schedule
            )
            """;

    /** The projection, shared so the queue and the detail banner can never disagree about a row. */
    private static final String SELECT_SQL = """
            with
            """ + CHARGE_OF_INVOICE + """
            ,
            agg as (
              select id_invoice,
                     count(*)                                          as charge_count,
                     -- Live means "a payer could pay it today", which needs the deadline as well as
                     -- the status: expiry is enforced at read time, so an ACTIVE charge past its
                     -- date has had its VA retired and answers nothing. Counting it as live hid a
                     -- genuinely unpayable receivable from this queue and told the invoice page it
                     -- was still collectible.
                     count(*) filter (where status in ('ACTIVE','PARTIALLY_PAID')
                                        and expires_at is not null and expires_at > now()) as live_count,
                     max(cancelled_at)                                 as last_cancelled_at,
                     max(va_number)                                    as va_number
                from charge_of_invoice
               group by id_invoice
            )
            select i.id, i.invoice_number, i.source_bill_number, d.code, d.name, i.description,
                   i.due_date, i.outstanding,
                   a.va_number, a.last_cancelled_at, i.withdrawn_at, i.withdrawn_reason,
                   coalesce(a.charge_count, 0) > 0 as ever_charged,
                   (select count(*) from cash_application_line l
                     where l.id_invoice = i.id)    as cash_applications,
                   coalesce(sib.paid_amount, 0)    as paid_on_same_va,
                   sib.paid_bill
              from invoice i
              join debtor d on d.id = i.id_debtor
              left join agg a on a.id_invoice = i.id
              left join lateral (
                    -- A later charge on the same number that was paid. Reported, never trusted:
                    -- the number is reused, so this may be a different period's bill.
                    select sum(sc.amount) as paid_amount,
                           -- whichever number a reviewer can actually look up: the originating
                           -- system's if this invoice was mirrored, otherwise our own
                           min(coalesce(si.source_bill_number, si.invoice_number)) as paid_bill
                      from charge_of_invoice co
                      join charge sc on sc.id = co.id
                      join invoice si on si.id = co.id_invoice
                     where co.va_number = a.va_number
                       and co.id_invoice <> i.id
                       and sc.status = 'PAID'
                   ) sib on true
            """;

    /**
     * Rows needing a decision. Three conditions decide membership, each load-bearing:
     *
     * <ul>
     *   <li><b>Still owed.</b> Open or partly paid, with a balance. A settled or already-forgiven
     *       receivable needs nothing.</li>
     *   <li><b>Nothing live to collect with.</b> No charge is still collectible. A superseded charge
     *       whose replacement is active does not belong here — that debt is payable right now.</li>
     *   <li><b>Something actually happened.</b> Either the source system withdrew the bill, or a
     *       charge was cancelled inside the window. Never merely "has no charge": twenty thousand
     *       migrated historical invoices satisfy that, and burying eight years of uncollected history
     *       in a queue meant for the handful that changed is how a queue gets abandoned. A
     *       never-billed invoice enters only when a withdrawal names it.</li>
     * </ul>
     *
     * <p>Rows a reviewer already kept are excluded, so deliberate decisions do not reappear each
     * morning beside rows nobody has read.
     */
    private static final String QUEUE_SQL = SELECT_SQL + """
             where i.payment_status in ('OPEN','PARTIALLY_PAID')
               and i.outstanding > 0
               and coalesce(a.live_count, 0) = 0
               and i.review_kept_at is null
               and (i.withdrawn_at is not null or a.last_cancelled_at >= ?)
             order by coalesce(i.withdrawn_at, a.last_cancelled_at) desc
             limit ? offset ?
            """;

    /**
     * The same evidence for one invoice, with the queue's membership rules dropped.
     *
     * <p>An operator chasing a particular student arrives at the invoice, not at the queue, and what
     * the screen says there is "OPEN, overdue" — an instruction to go and collect. On 2026-08-19 an
     * operator followed exactly that and moved a due date on a receivable whose debt had been
     * collected under a replacement bill eight days earlier. Evidence that lives only in the queue
     * is evidence for people who were already suspicious.
     */
    private static final String ONE_SQL = SELECT_SQL + """
             where i.id = ?
               and i.payment_status in ('OPEN','PARTIALLY_PAID')
               and i.outstanding > 0
               and coalesce(a.live_count, 0) = 0
            """;

    private static final String COUNT_SQL = """
            with
            """ + CHARGE_OF_INVOICE + """
            ,
            agg as (
              select id_invoice,
                     -- Live means "a payer could pay it today", which needs the deadline as well as
                     -- the status: expiry is enforced at read time, so an ACTIVE charge past its
                     -- date has had its VA retired and answers nothing. Counting it as live hid a
                     -- genuinely unpayable receivable from this queue and told the invoice page it
                     -- was still collectible.
                     count(*) filter (where status in ('ACTIVE','PARTIALLY_PAID')
                                        and expires_at is not null and expires_at > now()) as live_count,
                     max(cancelled_at) as last_cancelled_at
                from charge_of_invoice
               group by id_invoice
            )
            select count(*)
              from invoice i
              left join agg a on a.id_invoice = i.id
             where i.payment_status in ('OPEN','PARTIALLY_PAID')
               and i.outstanding > 0
               and coalesce(a.live_count, 0) = 0
               and i.review_kept_at is null
               and (i.withdrawn_at is not null or a.last_cancelled_at >= ?)
            """;

    private final JdbcTemplate jdbc;
    private final InvoiceRepository invoiceRepository;
    private final CashApplicationRepository cashApplicationRepository;
    private final AuditService auditService;
    private final Clock clock;

    public ReceivableReviewService(JdbcTemplate jdbc, InvoiceRepository invoiceRepository,
                                   CashApplicationRepository cashApplicationRepository,
                                   AuditService auditService, Clock clock) {
        this.jdbc = jdbc;
        this.invoiceRepository = invoiceRepository;
        this.cashApplicationRepository = cashApplicationRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    private static final org.springframework.jdbc.core.RowMapper<ReviewQueueItem> ROW =
            (rs, n) -> new ReviewQueueItem(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                    rs.getString(5), rs.getString(6),
                    rs.getObject(7, java.time.LocalDate.class),
                    rs.getBigDecimal(8), rs.getString(9),
                    instant(rs.getTimestamp(10)), instant(rs.getTimestamp(11)), rs.getString(12),
                    rs.getBoolean(13), rs.getLong(14),
                    rs.getBigDecimal(15), rs.getString(16));

    @Transactional(readOnly = true)
    public List<ReviewQueueItem> queue(int windowDays, int page, int size) {
        Timestamp since = Timestamp.from(Instant.now(clock).minus(Duration.ofDays(windowDays)));
        return jdbc.query(QUEUE_SQL, ROW, since, size, (long) page * size);
    }

    /**
     * The evidence for one receivable, for the screen an operator actually lands on. Empty when the
     * receivable is settled or still has a live charge — in those cases there is nothing to warn
     * about and a banner would be noise.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<ReviewQueueItem> evidenceFor(String invoiceId) {
        return jdbc.query(ONE_SQL, ROW, invoiceId).stream().findFirst();
    }

    @Transactional(readOnly = true)
    public long count(int windowDays) {
        Timestamp since = Timestamp.from(Instant.now(clock).minus(Duration.ofDays(windowDays)));
        Long n = jdbc.queryForObject(COUNT_SQL, Long.class, since);
        return n == null ? 0 : n;
    }

    /**
     * Record that the originating system retired this bill. Evidence only — the receivable keeps its
     * status and its balance, and a person decides what follows.
     *
     * <p>A fresh withdrawal clears any earlier "keep collecting" decision, because it is new
     * evidence: whoever kept the row was reasoning from a bill the source system had not yet
     * retired, and their conclusion deserves re-examination rather than silent survival.
     */
    @Transactional
    public void withdraw(String invoiceId, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("A withdrawal reason is required");
        }
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
        invoice.setWithdrawnAt(Instant.now(clock));
        invoice.setWithdrawnReason(reason.strip());
        invoice.setReviewKeptAt(null);
        invoice.setReviewNote(null);
        invoiceRepository.save(invoice);
        auditService.record("INVOICE_WITHDRAWN_UPSTREAM", "Invoice", invoiceId,
                invoice.getInvoiceNumber() + " outstanding=" + invoice.getOutstanding()
                        + " reason=" + reason.strip());
    }

    /**
     * Record that a reviewer judged this receivable still collectible, and drop it from the queue.
     *
     * <p>The note is required for the same reason a write-off reason is: this is a judgement against
     * the evidence on screen, and the next person needs to know what it was. Keeping a receivable is
     * reversible — the row returns the moment new evidence arrives — so it is not guarded as tightly
     * as a write-off, but it is recorded just as carefully.
     */
    @Transactional
    public void keepCollecting(String invoiceId, String note) {
        if (note == null || note.isBlank()) {
            throw new InvalidRequestException("A reason for keeping this receivable is required");
        }
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
        if (invoice.getPaymentStatus() != PaymentStatus.OPEN
                && invoice.getPaymentStatus() != PaymentStatus.PARTIALLY_PAID) {
            throw new InvalidRequestException(
                    "Cannot review an invoice in status " + invoice.getPaymentStatus());
        }
        invoice.setReviewKeptAt(Instant.now(clock));
        invoice.setReviewNote(note.strip());
        invoiceRepository.save(invoice);
        auditService.record("INVOICE_REVIEW_KEPT", "Invoice", invoiceId,
                invoice.getInvoiceNumber() + " outstanding=" + invoice.getOutstanding()
                        + " note=" + note.strip());
    }

    /**
     * Record that the originating billing system never booked a payment we accepted.
     *
     * <p>Only an external check can see this: our own books are correct — the receivable is paid and
     * the cash applied — and the divergence exists entirely in the other system. So the fact is
     * pushed in by whatever compares the two, keyed on the gateway payment reference, which is the
     * one identifier both sides share.
     *
     * <p>Flagging is idempotent on that reference: a check that runs daily must be able to re-report
     * the same unresolved divergence without stacking duplicates or resetting the moment it was
     * first seen, which is what tells a reviewer how long it has been outstanding.
     */
    @Transactional
    public void flagUpstreamMissing(String gatewayPaymentReference, String note) {
        if (note == null || note.isBlank()) {
            throw new InvalidRequestException("A note describing what the check found is required");
        }
        CashApplication ca = cashApplicationRepository
                .findByGatewayPaymentReference(gatewayPaymentReference)
                .orElseThrow(() -> new NotFoundException(
                        "No cash application for reference " + gatewayPaymentReference));
        if (ca.getUpstreamMissingAt() != null && ca.getUpstreamClearedAt() == null) {
            return; // already open — keep the original moment
        }
        ca.setUpstreamMissingAt(Instant.now(clock));
        ca.setUpstreamMissingNote(note.strip());
        ca.setUpstreamClearedAt(null);
        ca.setUpstreamClearedNote(null);
        cashApplicationRepository.save(ca);
        auditService.record("PAYMENT_MISSING_UPSTREAM", "CashApplication", ca.getId(),
                "reference=" + gatewayPaymentReference + " amount=" + ca.getAmount()
                        + " note=" + note.strip());
    }

    /**
     * A person dealt with it upstream. Cleared rather than deleted, so a debtor whose payments keep
     * going missing is distinguishable from a one-off.
     */
    @Transactional
    public void clearUpstreamMissing(String cashApplicationId, String note) {
        if (note == null || note.isBlank()) {
            throw new InvalidRequestException("A note saying how it was resolved is required");
        }
        CashApplication ca = cashApplicationRepository.findById(cashApplicationId)
                .orElseThrow(() -> new NotFoundException(
                        "Cash application not found: " + cashApplicationId));
        if (ca.getUpstreamMissingAt() == null) {
            throw new InvalidRequestException("This payment was never flagged as missing upstream");
        }
        ca.setUpstreamClearedAt(Instant.now(clock));
        ca.setUpstreamClearedNote(note.strip());
        cashApplicationRepository.save(ca);
        auditService.record("PAYMENT_MISSING_UPSTREAM_CLEARED", "CashApplication", ca.getId(),
                "reference=" + ca.getGatewayPaymentReference() + " note=" + note.strip());
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<CashApplication> upstreamMissing(
            org.springframework.data.domain.Pageable pageable) {
        return cashApplicationRepository.findUpstreamMissing(pageable);
    }

    @Transactional(readOnly = true)
    public long countUpstreamMissing() {
        return cashApplicationRepository.countUpstreamMissing();
    }

    private static Instant instant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
