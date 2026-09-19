package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.contract.ContractEventService;
import com.artivisi.accountreceivable.dto.AnomalyQueueItem;
import com.artivisi.accountreceivable.dto.CashApplicationResponse;
import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.CashApplicationLine;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import com.artivisi.accountreceivable.entity.Charge;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import com.artivisi.accountreceivable.entity.ReceivableAnomaly;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.CashApplicationRepository;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.repository.ReceivableAnomalyRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The decisions taken on findings raised from outside AR (see V12).
 *
 * <p>A finding leaves the queue one of two ways. {@link #resolve} records a decision that leaves the
 * ledger alone: the money was already booked elsewhere, refunded, reversed by the bank. {@link
 * #bookPayment} exists for the one finding whose remedy IS a ledger entry — the bank received the
 * money and no book recorded it, so the student is still billed for a debt they paid. Until this
 * existed the only instruments to hand were write-off and credit note, and both would record a
 * collection as a loss.
 *
 * <p>Booking takes the bank's figures from the finding (V14) and never from the operator. What the
 * operator supplies is the confirmation that the bank's record matches, and the note saying so.
 */
@Service
public class ReceivableAnomalyService {

    public static final String BANK_PAID_NOT_BOOKED = "BANK_PAID_NOT_BOOKED";
    public static final String PAYMENT_BOOKED = "PAYMENT_BOOKED";

    /**
     * Decisions an operator may record without touching the ledger, in the order offered.
     * {@link #PAYMENT_BOOKED} is absent on purpose: it asserts that cash was booked, so only
     * {@link #bookPayment} may write it.
     */
    public static final List<String> MANUAL_RESOLUTIONS =
            List.of("ALREADY_RECORDED", "REFUNDED", "BANK_REVERSED", "NOT_THIS_RECEIVABLE", "OTHER");

    /** Leaves room for the booking prefix inside cash_application.note (512). */
    private static final int NOTE_MAX = 400;
    private static final Pattern DIGITS = Pattern.compile("[0-9]+");

    private final ReceivableAnomalyRepository anomalyRepository;
    private final CashApplicationRepository cashApplicationRepository;
    private final ChargeRepository chargeRepository;
    private final InvoiceService invoiceService;
    private final ContractEventService contractEvents;
    private final AuditService auditService;
    private final Clock clock;

    public ReceivableAnomalyService(ReceivableAnomalyRepository anomalyRepository,
                                    CashApplicationRepository cashApplicationRepository,
                                    ChargeRepository chargeRepository,
                                    InvoiceService invoiceService,
                                    ContractEventService contractEvents,
                                    AuditService auditService,
                                    Clock clock) {
        this.anomalyRepository = anomalyRepository;
        this.cashApplicationRepository = cashApplicationRepository;
        this.chargeRepository = chargeRepository;
        this.invoiceService = invoiceService;
        this.contractEvents = contractEvents;
        this.auditService = auditService;
        this.clock = clock;
    }

    /** Open findings, oldest first: how long one has waited is the reviewer's main signal. */
    @Transactional(readOnly = true)
    public Page<AnomalyQueueItem> openQueue(int page, int size) {
        return anomalyRepository.findByResolvedAtIsNull(PageRequest.of(Math.max(page, 0), size,
                        Sort.by("createdAt").ascending().and(Sort.by("id"))))
                .map(this::item);
    }

    @Transactional(readOnly = true)
    public long countOpen() {
        return anomalyRepository.countByResolvedAtIsNull();
    }

    /** The open findings for one invoice, for the screen an operator lands on. */
    @Transactional(readOnly = true)
    public List<AnomalyQueueItem> openForInvoice(String invoiceId) {
        return anomalyRepository.findByInvoiceIdAndResolvedAtIsNullOrderByCreatedAtAsc(invoiceId).stream()
                .map(this::item)
                .toList();
    }

    /**
     * Close a finding with a decision that books nothing. Resolution and note are both required:
     * a finding that leaves the queue without saying who took it out and why is how the queue stops
     * being trusted (the V12 check constraint enforces the same at the table).
     */
    @Transactional
    public void resolve(String anomalyId, String resolution, String note, String actor) {
        requireActor(actor);
        if (resolution == null || !MANUAL_RESOLUTIONS.contains(resolution)) {
            throw new InvalidRequestException("Resolution must be one of " + MANUAL_RESOLUTIONS
                    + (PAYMENT_BOOKED.equals(resolution)
                       ? "; PAYMENT_BOOKED is recorded only by booking the payment" : ""));
        }
        String why = requireNote(note);
        ReceivableAnomaly finding = loadOpen(anomalyId);
        close(finding, resolution, why, actor);
        Invoice invoice = finding.getInvoice();
        auditService.record("ANOMALY_RESOLVED", "Invoice", invoice.getId(),
                invoice.getInvoiceNumber() + " finding=" + finding.getId() + " category=" + finding.getCategory()
                        + " resolution=" + resolution + " by=" + actor + " note=" + why);
    }

    /**
     * Book the payment a BANK_PAID_NOT_BOOKED finding describes, and close the finding.
     *
     * <p>Allocation is the same as a gateway payment's: a plan settles its earliest unpaid legs, a
     * single invoice takes the whole amount. Campus apps are told through {@code payment.received},
     * because their copy of the receivable is derived from these events and still shows the debt.
     * No receipt is sent to the payer — the payment happened weeks or years ago and a receipt now
     * would read as a new charge.
     *
     * <p>The charge on the finding is linked but not touched. Where the gateway already holds this
     * payment, its charge mirror is already settled; adding the amount again would double it.
     *
     * <p>Every refusal happens before anything is written, and none of them parks the money: an
     * over-payment or a receivable already closed is a decision for a person.
     */
    @Transactional
    public CashApplicationResponse bookPayment(String anomalyId, String note, String actor) {
        requireActor(actor);
        String why = requireNote(note);
        ReceivableAnomaly finding = loadOpen(anomalyId);
        bookingRefusal(finding).ifPresent(reason -> {
            throw new InvalidRequestException(reason);
        });

        Invoice invoice = finding.getInvoice();
        BigDecimal amount = finding.getEvidenceAmount();
        CashApplication application = new CashApplication();
        application.setGatewayPaymentReference(finding.getEvidenceRef());
        application.setCharge(finding.getCharge());
        application.setAmount(amount);
        application.setCurrency(invoice.getCurrency());
        application.setReceivedAt(finding.getEvidenceAt());
        application.setNote("Booked from receivable finding " + finding.getId() + ": " + why);
        if (invoice.isInstallment()) {
            for (InvoiceService.PlanAllocation allocation
                    : invoiceService.allocatePlanPayment(invoice.getId(), amount)) {
                CashApplicationLine line = new CashApplicationLine();
                line.setAllocatedAmount(allocation.amount());
                line.setInstallment(allocation.installment());
                application.addLine(line);
            }
        } else {
            invoiceService.applyInvoicePayment(invoice.getId(), amount);
            CashApplicationLine line = new CashApplicationLine();
            line.setAllocatedAmount(amount);
            line.setInvoice(invoice);
            application.addLine(line);
        }
        application.setStatus(CashApplicationStatus.APPLIED);
        CashApplication saved = cashApplicationRepository.save(application);

        contractEvents.paymentReceived(finding.getEvidenceVaNumber(), finding.getEvidenceBank(), saved, invoice);
        close(finding, PAYMENT_BOOKED, why, actor);
        auditService.record("ANOMALY_PAYMENT_BOOKED", "Invoice", invoice.getId(),
                invoice.getInvoiceNumber() + " finding=" + finding.getId()
                        + " reference=" + finding.getEvidenceRef() + " amount=" + amount
                        + " receivedAt=" + finding.getEvidenceAt() + " va=" + finding.getEvidenceVaNumber()
                        + " bank=" + finding.getEvidenceBank() + " status=" + invoice.getPaymentStatus()
                        + " by=" + actor + " note=" + why);
        return CashApplicationResponse.from(saved);
    }

    /**
     * Why this finding cannot be booked, or empty when it can. The single source for both the
     * refusal and the screen, so the button is never offered for something the service refuses.
     */
    private Optional<String> bookingRefusal(ReceivableAnomaly finding) {
        if (!BANK_PAID_NOT_BOOKED.equals(finding.getCategory())) {
            return Optional.of("Only a " + BANK_PAID_NOT_BOOKED + " finding can be booked as a payment;"
                    + " this one is " + finding.getCategory());
        }
        List<String> missing = new ArrayList<>();
        if (isBlank(finding.getEvidenceRef())) {
            missing.add("bank reference");
        }
        if (finding.getEvidenceAmount() == null) {
            missing.add("amount");
        }
        if (finding.getEvidenceAt() == null) {
            missing.add("time");
        }
        if (isBlank(finding.getEvidenceVaNumber())) {
            missing.add("VA number");
        }
        if (isBlank(finding.getEvidenceBank())) {
            missing.add("bank");
        }
        if (!missing.isEmpty()) {
            return Optional.of("The finding does not record the bank's " + String.join(", ", missing)
                    + "; a payment is booked from what the bank recorded, never typed in");
        }
        if (finding.getEvidenceAmount().signum() <= 0) {
            return Optional.of("The finding records a non-positive amount " + finding.getEvidenceAmount());
        }
        if (!DIGITS.matcher(finding.getEvidenceVaNumber()).matches()) {
            return Optional.of("The finding's VA number " + finding.getEvidenceVaNumber() + " is not all digits");
        }
        Optional<CashApplication> existing =
                cashApplicationRepository.findByGatewayPaymentReference(finding.getEvidenceRef());
        if (existing.isPresent()) {
            return Optional.of("Bank reference " + finding.getEvidenceRef() + " is already booked"
                    + heldBy(existing.get()) + "; resolve this finding as ALREADY_RECORDED instead");
        }
        Invoice invoice = finding.getInvoice();
        if (invoice.getPaymentStatus() != PaymentStatus.OPEN
                && invoice.getPaymentStatus() != PaymentStatus.PARTIALLY_PAID) {
            return Optional.of("Invoice " + invoice.getInvoiceNumber() + " is " + invoice.getPaymentStatus()
                    + "; a payment is booked only against an open receivable");
        }
        // Booking changes what is owed but not what the VA answers. A live charge would keep asking
        // for the old figure, and a payer who pays it settles the same debt twice.
        Optional<Charge> live = liveCharge(invoice);
        if (live.isPresent()) {
            return Optional.of("Invoice " + invoice.getInvoiceNumber() + " still has a live VA "
                    + live.get().getVaNumber() + " collecting " + live.get().getAmount()
                    + "; booking would leave it asking for the old amount. Cancel or reprice that charge first");
        }
        if (finding.getEvidenceAmount().compareTo(invoice.getOutstanding()) > 0) {
            return Optional.of("Amount " + finding.getEvidenceAmount() + " exceeds the outstanding "
                    + invoice.getOutstanding() + " on invoice " + invoice.getInvoiceNumber()
                    + "; decide where the excess belongs before booking");
        }
        return Optional.empty();
    }

    /**
     * A charge that can still take money for this invoice, directly or through one of its legs.
     * Expiry is enforced at read time, so an ACTIVE charge past its date no longer answers; one with
     * no expiry recorded is treated as live.
     */
    private Optional<Charge> liveCharge(Invoice invoice) {
        Instant now = Instant.now(clock);
        List<Charge> charges = new ArrayList<>(chargeRepository.findByInvoiceId(invoice.getId()));
        if (invoice.isInstallment() && invoice.getSchedule() != null) {
            invoice.getSchedule().getInstallments()
                    .forEach(leg -> charges.addAll(chargeRepository.findByInstallmentId(leg.getId())));
        }
        return charges.stream()
                .filter(c -> c.getStatus() == ChargeStatus.ACTIVE || c.getStatus() == ChargeStatus.PARTIALLY_PAID)
                .filter(c -> c.getExpiresAt() == null || c.getExpiresAt().isAfter(now))
                .findFirst();
    }

    /** Which invoice an existing cash application settled, so the refusal names where to look. */
    private static String heldBy(CashApplication application) {
        List<String> numbers = application.getLines().stream()
                .map(line -> line.getInvoice() != null
                        ? line.getInvoice().getInvoiceNumber()
                        : line.getInstallment().getSchedule().getInvoice().getInvoiceNumber())
                .distinct()
                .toList();
        if (!numbers.isEmpty()) {
            return " on invoice " + String.join(", ", numbers);
        }
        return " as cash application " + application.getId() + " (" + application.getStatus()
                + ", no allocation)";
    }

    private AnomalyQueueItem item(ReceivableAnomaly finding) {
        Invoice invoice = finding.getInvoice();
        Optional<String> refusal = bookingRefusal(finding);
        boolean bookableCategory = BANK_PAID_NOT_BOOKED.equals(finding.getCategory());
        return new AnomalyQueueItem(
                finding.getId(), invoice.getId(), invoice.getInvoiceNumber(), invoice.getSourceBillNumber(),
                invoice.getDebtor().getCode(), invoice.getDebtor().getName(),
                invoice.getPaymentStatus().name(), invoice.getOutstanding(),
                finding.getCategory(), finding.getSource(), finding.getDetail(),
                finding.getEvidenceRef(), finding.getEvidenceAmount(), finding.getEvidenceAt(),
                finding.getEvidenceVaNumber(), finding.getEvidenceBank(),
                finding.getRaisedBy(), finding.getCreatedAt(),
                ChronoUnit.DAYS.between(finding.getCreatedAt(), Instant.now(clock)),
                refusal.isEmpty(),
                bookableCategory ? refusal.orElse(null) : null);
    }

    private ReceivableAnomaly loadOpen(String anomalyId) {
        ReceivableAnomaly finding = anomalyRepository.findById(anomalyId)
                .orElseThrow(() -> new NotFoundException("Receivable finding not found: " + anomalyId));
        if (!finding.open()) {
            throw new InvalidRequestException("Finding " + anomalyId + " was already decided: "
                    + finding.getResolution() + " by " + finding.getResolvedBy());
        }
        return finding;
    }

    private void close(ReceivableAnomaly finding, String resolution, String note, String actor) {
        finding.setResolvedAt(Instant.now(clock));
        finding.setResolvedBy(actor);
        finding.setResolution(resolution);
        finding.setResolutionNote(note);
        anomalyRepository.save(finding);
    }

    private static String requireNote(String note) {
        if (isBlank(note)) {
            throw new InvalidRequestException("A note saying what was checked is required");
        }
        String stripped = note.strip();
        if (stripped.length() > NOTE_MAX) {
            throw new InvalidRequestException("The note is " + stripped.length()
                    + " characters; the limit is " + NOTE_MAX);
        }
        return stripped;
    }

    private static void requireActor(String actor) {
        if (isBlank(actor)) {
            throw new IllegalStateException("A decision on a finding needs the deciding operator");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
