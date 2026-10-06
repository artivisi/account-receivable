package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.client.GatewayChargeRequest;
import com.artivisi.accountreceivable.client.GatewayChargeResponse;
import com.artivisi.accountreceivable.client.GatewayConsumerClient;
import com.artivisi.accountreceivable.config.ArGatewayProperties;
import com.artivisi.accountreceivable.config.ArNotificationProperties;
import com.artivisi.accountreceivable.service.notification.NotificationPayloadMapper;
import com.artivisi.accountreceivable.dto.CashApplicationListItem;
import com.artivisi.accountreceivable.dto.CashApplicationResponse;
import com.artivisi.accountreceivable.dto.ChargeListItem;
import com.artivisi.accountreceivable.dto.ChargeResponse;
import com.artivisi.accountreceivable.entity.PaymentSchedule;
import com.artivisi.accountreceivable.dto.CreditNoteRequest;
import com.artivisi.accountreceivable.dto.CreditNoteResponse;
import com.artivisi.accountreceivable.dto.InvoiceResponse;
import com.artivisi.accountreceivable.dto.IssueInvoiceRequest;
import com.artivisi.accountreceivable.dto.DueDateOutcome;
import com.artivisi.accountreceivable.dto.GatewayWebhookPayload;
import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.CashApplicationLine;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import com.artivisi.accountreceivable.entity.Charge;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import org.springframework.web.client.HttpClientErrorException;
import java.util.List;
import com.artivisi.accountreceivable.entity.ChargeType;
import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.entity.Installment;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.NotificationSourceType;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.CashApplicationRepository;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.repository.InstallmentRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import com.artivisi.accountreceivable.spi.VaAllocationContext;
import com.artivisi.accountreceivable.spi.VaNumberSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CollectionService {

    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);

    /**
     * Separates a target id from its collection generation in a consumer reference, e.g.
     * {@code <invoiceId>#2}. Deliberately not {@code :}, which the 8.5-year history import already
     * used for the bank code ({@code <invoiceId>:451}).
     */
    private static final String GENERATION_SEPARATOR = "#";

    /**
     * How many generations to walk before giving up on finding an unused reference. Each spent one
     * costs a gateway round trip, and needing more than a couple means something is wrong that
     * another attempt will not fix.
     */
    private static final int MAX_GENERATION_ATTEMPTS = 10;

    /** {@code paidAtLocal} for hubs that print the payment time as local wall-clock text. */
    private static final DateTimeFormatter PAID_AT_LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ChargeRepository chargeRepository;
    private final CashApplicationRepository cashApplicationRepository;
    private final InvoiceRepository invoiceRepository;
    private final InstallmentRepository installmentRepository;
    private final InvoiceService invoiceService;
    private final CreditNoteService creditNoteService;
    private final ChargeCancellationService chargeCancellationService;
    private final GatewayConsumerClient gatewayClient;
    private final VaNumberSupplier vaNumberSupplier;
    private final NotificationService notificationService;
    private final ArGatewayProperties gatewayProperties;
    private final ArNotificationProperties notificationProperties;
    private final NotificationPayloadMapper payloadMapper;
    private final AuditService auditService;
    private final Clock clock;
    private final com.artivisi.accountreceivable.contract.ContractEventService contractEvents;

    public CollectionService(ChargeRepository chargeRepository,
                             CashApplicationRepository cashApplicationRepository,
                             InvoiceRepository invoiceRepository,
                             InstallmentRepository installmentRepository,
                             InvoiceService invoiceService,
                             CreditNoteService creditNoteService,
                             ChargeCancellationService chargeCancellationService,
                             GatewayConsumerClient gatewayClient,
                             VaNumberSupplier vaNumberSupplier,
                             NotificationService notificationService,
                             ArGatewayProperties gatewayProperties,
                             ArNotificationProperties notificationProperties,
                             NotificationPayloadMapper payloadMapper,
                             AuditService auditService,
                             com.artivisi.accountreceivable.contract.ContractEventService contractEvents, Clock clock) {
        this.chargeRepository = chargeRepository;
        this.cashApplicationRepository = cashApplicationRepository;
        this.invoiceRepository = invoiceRepository;
        this.installmentRepository = installmentRepository;
        this.invoiceService = invoiceService;
        this.creditNoteService = creditNoteService;
        this.chargeCancellationService = chargeCancellationService;
        this.gatewayClient = gatewayClient;
        this.vaNumberSupplier = vaNumberSupplier;
        this.notificationService = notificationService;
        this.gatewayProperties = gatewayProperties;
        this.notificationProperties = notificationProperties;
        this.payloadMapper = payloadMapper;
        this.auditService = auditService;
        this.clock = clock;
        this.contractEvents = contractEvents;
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<ChargeListItem> listCharges(
            String q, org.springframework.data.domain.Pageable p) {
        String qFilter = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        return chargeRepository.search(qFilter, p);
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<CashApplicationListItem> listCashApplications(
            CashApplicationStatus status, org.springframework.data.domain.Pageable p) {
        org.springframework.data.domain.Page<CashApplication> page = (status == null)
                ? cashApplicationRepository.findAll(p)
                : cashApplicationRepository.findByStatus(status, p);
        return page.map(CashApplicationListItem::from);
    }

    @Transactional(readOnly = true)
    public long countUnappliedCashApplications() {
        return cashApplicationRepository.countByStatus(CashApplicationStatus.UNAPPLIED);
    }

    /**
     * Open the charge that collects an invoice. A single-payment invoice is collected for its whole
     * outstanding amount; an instalment plan is collected on ONE charge whose amount is what the plan
     * is worth today ({@link PaymentSchedule#amountDueOn}), repriced as instalments fall due and
     * reopened on the same VA number after each one is paid.
     */
    @Transactional
    public ChargeResponse openChargeForInvoice(String invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
        assertCollectible(invoice.getPaymentStatus(), "invoice " + invoice.getInvoiceNumber());
        BigDecimal amount = invoice.isInstallment()
                ? invoice.getSchedule().amountDueOn(today()) : invoice.getOutstanding();
        return ChargeResponse.from(openCharge(invoiceId, invoice, null,
                amount, invoice.getCurrency(), invoice.getDebtor().getName()));
    }

    /**
     * Open the charge for an invoice deliberately issued without one, refusing if a sibling is still
     * collecting on the number.
     *
     * <p>Separate from {@link #openChargeForInvoice} because the two want opposite things from a busy
     * number. Issuing an invoice that takes over the number is ordinary and intended — a new
     * semester's bill supersedes last semester's VA, and that is how the payer keeps one number. But
     * when every instalment is issued up front and collected one at a time, the sibling holding the
     * number is not stale: it is the leg being paid right now, and superseding it would stop that
     * payment without anyone asking for it.
     */
    @Transactional
    public ChargeResponse openDeferredChargeForInvoice(String invoiceId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
        assertNumberNotHeldBySibling(invoice);
        return openChargeForInvoice(invoiceId);
    }

    /**
     * Refuse to open a charge on a VA number a different invoice is still collecting on.
     *
     * <p>The number is {@code type + debtor}, so every invoice of one type for one payer resolves to
     * the same number — which is exactly the shape a campus app produces when it issues each
     * instalment as its own invoice up front and collects them one at a time. Opening the second one
     * looks harmless: this invoice has no charge of its own, so nothing local objects. But the
     * gateway call supersedes whatever holds the number, so the quiet outcome is that the leg the
     * payer is in the middle of paying stops being payable, and the first anyone knows is a failed
     * payment at the ATM.
     *
     * <p>Reopening within one invoice is untouched — rollover and the reopen after a spent
     * generation both reuse the number deliberately, and both are the same invoice.
     */
    private void assertNumberNotHeldBySibling(Invoice invoice) {
        String vaNumber = vaNumberSupplier.allocate(new VaAllocationContext(
                gatewayProperties.escrowCode(), invoice.getId(),
                invoice.getDebtor().getCode(), invoice.getInvoiceType().getCode()));
        for (Charge held : chargeRepository.findByVaNumberAndStatusIn(
                vaNumber, List.of(ChargeStatus.ACTIVE, ChargeStatus.PARTIALLY_PAID))) {
            Invoice holder = held.getInvoice() != null ? held.getInvoice()
                    : (held.getInstallment() != null ? held.getInstallment().getSchedule().getInvoice() : null);
            if (holder != null && !holder.getId().equals(invoice.getId())) {
                throw new InvalidRequestException("VA_NUMBER_BUSY",
                        "VA " + vaNumber + " is still collecting invoice " + holder.getInvoiceNumber()
                                + "; that invoice must be paid or cancelled before "
                                + invoice.getInvoiceNumber() + " can be collected on the same number");
            }
        }
    }

    /**
     * Replace the unpaid part of an instalment plan, then bring the plan's charge into step with it.
     * Also how a plan is settled early: one new leg for the whole remainder, due today.
     */
    @Transactional
    public InvoiceResponse amendPlan(String invoiceId, List<IssueInvoiceRequest.InstallmentRequest> legs,
                                     String reason, String reference, String decidedBy, String correlationId) {
        Invoice invoice = invoiceService.amendPlan(invoiceId, legs, reason, reference);
        contractEvents.planAmended(invoice, correlationId, reason, reference, decidedBy);
        syncPlanCharge(invoice, "PLAN_AMENDED");
        return InvoiceResponse.from(invoice, today());
    }

    /**
     * Correct what an invoice is worth and carry it to the gateway. A single-payment invoice's live
     * charge is repriced to the new outstanding; a plan's follows {@link #syncPlanCharge}. Reducing
     * to zero cancels the invoice, and its charges through the cancellation outbox.
     */
    @Transactional
    public InvoiceResponse amendAmount(String invoiceId, BigDecimal amount, String reason, String reference) {
        Invoice invoice = invoiceService.amendAmount(invoiceId, amount, reason, reference);
        carryToGateway(invoice, "INVOICE_AMENDED");
        return InvoiceResponse.from(invoice, today());
    }

    /**
     * Issue a credit note and carry what it did to the gateway.
     *
     * <p>The gateway step is the whole reason this wrapper exists. A scholarship or discount recorded
     * only in the ledger leaves a VA still asking the payer for the old amount, and someone pays it:
     * that is how a settled bill gets paid a second time. A bill left owing nothing has its charge
     * cancelled; one owing less has it repriced.
     */
    @Transactional
    public CreditNoteResponse issueCreditNote(CreditNoteRequest request) {
        CreditNoteResponse note = creditNoteService.issue(request);
        Invoice invoice = invoiceRepository.findById(request.invoiceId()).orElseThrow(
                () -> new NotFoundException("Invoice not found: " + request.invoiceId()));
        carryToGateway(invoice, "CREDIT_NOTE");
        return note;
    }

    /** Bring the live charges into step with what the invoice now owes. */
    private void carryToGateway(Invoice invoice, String cause) {
        if (!invoice.isCollectible()) {
            return;   // cancelling the invoice already enqueued its charges
        }
        if (invoice.getOutstanding().signum() == 0) {
            chargeCancellationService.enqueueForWriteOff(invoice);
            return;
        }
        if (invoice.isInstallment()) {
            syncPlanCharge(invoice, cause);
            return;
        }
        for (Charge live : chargeRepository.findByInvoiceId(invoice.getId())) {
            if (live.getStatus() != ChargeStatus.ACTIVE || live.getAmount().compareTo(invoice.getOutstanding()) == 0) {
                continue;
            }
            gatewayClient.repriceCharge(live.getGatewayChargeId(), invoice.getOutstanding());
            live.setAmount(invoice.getOutstanding());
            chargeRepository.save(live);
            contractEvents.chargeRepriced(live, invoice, cause);
        }
    }

    /** Invoices whose live plan charge may have drifted from the plan — for the periodic sync. */
    @Transactional(readOnly = true)
    public List<String> findPlanInvoicesToSync() {
        return chargeRepository.findActivePlanCharges().stream()
                .map(c -> c.getInvoice().getId()).distinct().toList();
    }

    @Transactional
    public void syncPlanCharge(String invoiceId) {
        syncPlanCharge(invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId)), "INSTALLMENT_DUE");
    }

    /**
     * Make the plan's charge say what the plan says: the amount due today, expiring with the last
     * installment. Called after anything that changes either — a date moved, the plan amended, a day
     * passing — and after a payment leaves the plan without a live charge.
     *
     * <p>Gateway first, then our row, so a refusal at the gateway rolls the whole thing back rather
     * than leaving our figure ahead of the payer's.
     */
    private void syncPlanCharge(Invoice invoice, String repriceReason) {
        if (!invoice.isInstallment() || !invoice.isCollectible() || invoice.getOutstanding().signum() == 0) {
            return;
        }
        List<Charge> generations = chargeRepository.findByInvoiceId(invoice.getId());
        Charge live = generations.stream()
                .filter(c -> c.getStatus() == ChargeStatus.ACTIVE || c.getStatus() == ChargeStatus.PARTIALLY_PAID)
                .findFirst().orElse(null);
        BigDecimal due = invoice.getSchedule().amountDueOn(today());
        if (live == null) {
            // Collection was started for this plan and every generation is spent: reopen for the
            // remainder. A plan nobody has opened a charge for yet is left alone — opening is the
            // caller's step, not a side effect of amending.
            if (!generations.isEmpty()) {
                openCharge(invoice.getId(), invoice, null, due, invoice.getCurrency(),
                        invoice.getDebtor().getName(), false);
            }
            return;
        }
        if (live.getAmount().compareTo(due) != 0) {
            gatewayClient.repriceCharge(live.getGatewayChargeId(), due);
            auditService.record("CHARGE_REPRICED", "Invoice", invoice.getId(),
                    "consumerRef=" + live.getConsumerReference() + " from=" + live.getAmount() + " to=" + due);
            live.setAmount(due);
            contractEvents.chargeRepriced(live, invoice, repriceReason);
        }
        Instant expiresAt = expiryFor(invoice.getDueDate());
        if (!expiresAt.equals(live.getExpiresAt())) {
            gatewayClient.extendCharge(live.getGatewayChargeId(), expiresAt);
            live.setExpiresAt(expiresAt);
        }
        chargeRepository.save(live);
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    /** Idempotent lookup for a payment already applied — used to resolve concurrent webhook redelivery. */
    @Transactional(readOnly = true)
    public CashApplicationResponse getAppliedByReference(String gatewayPaymentReference) {
        return cashApplicationRepository.findByGatewayPaymentReference(gatewayPaymentReference)
                .map(CashApplicationResponse::from)
                .orElseThrow(() -> new NotFoundException(
                        "No cash application for reference " + gatewayPaymentReference));
    }

    /**
     * When the VA stops answering, for a bill due on {@code dueDate}.
     *
     * <p>The due date is INCLUSIVE — a bill due on the 31st is payable through the 31st — so the VA
     * lapses at the start of the following day. This is the semantic of the bank adapter the
     * gateway replaced, verified against its data rather than assumed: it refuses a bill once
     * {@code expire_date > current_date} fails, and stores {@code expire_date} as the due date plus
     * one for every bill. Expiring at the start of the due date would cut a day off every bill
     * relative to the system being replaced.
     */
    private Instant expiryFor(LocalDate dueDate) {
        return dueDate.plusDays(1).atStartOfDay(clock.getZone()).toInstant();
    }

    /**
     * Move an invoice's due date, and carry it through to the gateway so the payer sees it.
     *
     * <p>Exists because a corrected due date otherwise stops at our books. The originating system
     * republishes a revised bill under the SAME number; without this the receivable keeps the first
     * version, and once AR started sending expiry that meant a charge could be born already expired
     * — its VA retired by the sweep and a real payment refused at the bank.
     */
    @Transactional
    public DueDateOutcome amendDueDate(String invoiceId, LocalDate newDueDate, String reference) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));
        if (invoice.isInstallment()) {
            throw new InvalidRequestException(
                    "Installment invoice: amend the installment's due date, not the invoice's");
        }
        LocalDate previous = invoice.getDueDate();
        List<Charge> charges = chargeRepository.findByInvoiceId(invoiceId);
        if (newDueDate.equals(previous)) {
            return describe(charges, 0);
        }
        invoice.setDueDate(newDueDate);
        invoice.recomputeEarliestUnpaidDueDate();
        invoiceRepository.save(invoice);

        Instant expiresAt = expiryFor(newDueDate);
        int moved = 0;
        for (Charge charge : charges) {
            if (charge.getStatus() != ChargeStatus.ACTIVE && charge.getStatus() != ChargeStatus.PARTIALLY_PAID) {
                continue;
            }
            // Gateway first: if it refuses (the number is active on a newer charge), the whole
            // amendment rolls back rather than leaving our date ahead of the payer's.
            gatewayClient.extendCharge(charge.getGatewayChargeId(), expiresAt);
            charge.setExpiresAt(expiresAt);
            chargeRepository.save(charge);
            moved++;
        }
        DueDateOutcome outcome = describe(charges, moved);
        auditService.record("INVOICE_DUE_DATE_AMENDED", "Invoice", invoiceId,
                "from=" + previous + " to=" + newDueDate + " outcome=" + outcome.kind()
                        + (outcome.blockingBillNumber() == null ? ""
                           : " blockedBy=" + outcome.blockingBillNumber() + "/" + outcome.blockingStatus())
                        + (reference != null ? " reference=" + reference : ""));
        return outcome;
    }

    /**
     * Say what the amendment achieved, so the caller can tell the operator rather than flashing
     * "saved" over a receivable nobody can pay. The unpayable case names the bill that took the VA
     * number, because that is where the debt went and the next question is always "then which bill
     * do I chase?".
     */
    private DueDateOutcome describe(List<Charge> charges, int moved) {
        if (moved > 0) {
            return DueDateOutcome.restored(moved);
        }
        if (charges.isEmpty()) {
            return DueDateOutcome.noChargeYet();
        }
        String vaNumber = charges.get(charges.size() - 1).getVaNumber();
        return chargeRepository
                .findByVaNumberAndStatusIn(vaNumber, List.of(ChargeStatus.PAID, ChargeStatus.ACTIVE,
                        ChargeStatus.PARTIALLY_PAID))
                .stream()
                .filter(other -> charges.stream().noneMatch(c -> c.getId().equals(other.getId())))
                .findFirst()
                .map(other -> DueDateOutcome.stillUnpayable(billNumberOf(other), other.getStatus().name()))
                .orElseGet(() -> DueDateOutcome.stillUnpayable(null, null));
    }

    /** The number a person can look up: the originating system's if we mirrored one, else ours. */
    private static String billNumberOf(Charge charge) {
        Invoice inv = charge.getInvoice() != null
                ? charge.getInvoice()
                : charge.getInstallment().getSchedule().getInvoice();
        return inv.getSourceBillNumber() != null ? inv.getSourceBillNumber() : inv.getInvoiceNumber();
    }

    /**
     * Move one installment's due date, and carry it through to the gateway.
     *
     * <p>{@link #amendDueDate} refuses an installment invoice and points here, because a schedule
     * carries one deadline per installment and the invoice's own date says nothing about which one
     * the payer is late on. Until this existed that redirection led nowhere: an installment's
     * deadline could not be moved by any route, UI or API — and a monthly fee whose payer turns up
     * after the VA lapsed is the ordinary case, not the exotic one.
     */
    @Transactional
    public void amendInstallmentDueDate(String installmentId, LocalDate newDueDate, String reference) {
        Installment installment = installmentRepository.findById(installmentId)
                .orElseThrow(() -> new NotFoundException("Installment not found: " + installmentId));
        PaymentStatus status = installment.getPaymentStatus();
        if (status != PaymentStatus.OPEN && status != PaymentStatus.PARTIALLY_PAID) {
            throw new InvalidRequestException(
                    "Cannot move the due date of an installment in status " + status);
        }
        LocalDate previous = installment.getDueDate();
        if (newDueDate.equals(previous)) {
            return;
        }
        installment.setDueDate(newDueDate);
        installmentRepository.save(installment);

        // The invoice's overdue predicate reads the earliest unpaid installment date, so recompute
        // it here — otherwise the invoice keeps reporting overdue against a deadline that moved.
        Invoice invoice = installment.getSchedule().getInvoice();
        invoice.recomputeEarliestUnpaidDueDate();
        invoiceRepository.save(invoice);

        // The plan's deadline is its last installment's, and the charge follows both the deadline and
        // the amount due — a leg moved into the past rolls into what the VA answers today.
        invoice.setDueDate(invoice.getSchedule().lastDueDate());
        invoiceRepository.save(invoice);
        syncPlanCharge(invoice, "INSTALLMENT_DUE");
        auditService.record("INSTALLMENT_DUE_DATE_AMENDED", "Installment", installmentId,
                "invoice=" + invoice.getId() + " sequence=" + installment.getSequence()
                        + " from=" + previous + " to=" + newDueDate + (reference != null ? " reference=" + reference : ""));
    }

    private Charge openCharge(String targetReference, Invoice invoice, Installment installment,
                             BigDecimal amount, String currency, String payerName) {
        return openCharge(targetReference, invoice, installment, amount, currency, payerName, true);
    }

    /** {@code announce}: tell the payer a bill is ready. False when reopening a plan for its next leg. */
    private Charge openCharge(String targetReference, Invoice invoice, Installment installment,
                             BigDecimal amount, String currency, String payerName, boolean announce) {
        // Every charge ever opened for this target, in any state and any generation. Keyed on the
        // target rather than on the reference alone: a reopened collection carries a suffixed
        // reference, so the bare target id stops being the whole story after the first generation.
        List<Charge> generations = invoice != null
                ? chargeRepository.findByInvoiceId(invoice.getId())
                : chargeRepository.findByInstallmentId(installment.getId());
        // A charge that is live, part-paid or settled IS the answer — opening a second would put two
        // VAs on one debt. Only a dead one (cancelled or expired) leaves the target uncollected.
        // For a plan, a PAID generation is one leg settled, and the remainder still needs a charge.
        boolean plan = invoice != null && invoice.isInstallment();
        Charge collecting = generations.stream()
                .filter(c -> c.getStatus() != ChargeStatus.CANCELLED && c.getStatus() != ChargeStatus.EXPIRED)
                .filter(c -> !(plan && c.getStatus() == ChargeStatus.PAID))
                .findFirst()
                .orElse(null);
        if (collecting != null) {
            return collecting;
        }
        // Every generation is dead, and the caller has already established the target is still
        // collectible — so this is a reopen, most often the residue of a supersession whose
        // superseding bill has since been paid. It needs a fresh reference: the gateway is idempotent
        // on (consumer, consumerReference) and would hand back the cancelled charge rather than open
        // anything. Until this existed, POST /api/invoices/{id}/charge answered 201 with the dead
        // charge and opened nothing, so a receivable superseded once could never be collected again.
        int firstGeneration = generations.size() + 1;

        Invoice receivable = invoice != null ? invoice : installment.getSchedule().getInvoice();
        String vaNumber = vaNumberSupplier.allocate(new VaAllocationContext(
                gatewayProperties.escrowCode(),
                targetReference,
                receivable.getDebtor().getCode(),
                receivable.getInvoiceType().getCode()));
        // The bill number the bank/payer sees: the originating-system number if the invoice was
        // mirrored from one (legacy nomorTagihan), otherwise AR's own invoice number.
        String billNumber = receivable.getSourceBillNumber() != null
                ? receivable.getSourceBillNumber() : receivable.getInvoiceNumber();
        // When the VA stops answering. An installment carries its own deadline; a single-payment
        // invoice uses the invoice's.
        LocalDate dueDate = installment != null ? installment.getDueDate() : receivable.getDueDate();
        Instant expiresAt = expiryFor(dueDate);
        // What the payer reads on the ATM/mobile screen (BSI `keterangan`). Without it the bank
        // shows a blank description where the legacy system showed the bill's purpose.
        OpenedCharge opened = openLiveGatewayCharge(
                targetReference, firstGeneration, payerName, amount, vaNumber, billNumber,
                receivable.getDescription(), expiresAt);
        String consumerReference = opened.consumerReference();
        GatewayChargeResponse response = opened.response();

        Charge charge = new Charge();
        charge.setGatewayChargeId(response.id());
        charge.setConsumerReference(consumerReference);
        charge.setChargeType(ChargeType.CLOSED);
        charge.setInvoice(invoice);
        charge.setInstallment(installment);
        charge.setAmount(amount);
        charge.setCurrency(currency);
        charge.setStatus(chargeStatus(response.status()));
        charge.setCumulativePaid(BigDecimal.ZERO.setScale(2));
        charge.setEscrowCode(gatewayProperties.escrowCode());
        charge.setVaNumber(vaNumber);
        charge.setExpiresAt(expiresAt);
        Charge saved = chargeRepository.save(charge);
        contractEvents.chargeOpened(saved, receivable);
        auditService.record("CHARGE_OPENED", "Invoice", receivable.getId(),
                "consumerRef=" + consumerReference + " vaNumber=" + vaNumber + " amount=" + amount);
        if (announce) {
            notifyBillReady(receivable, invoice, installment, amount, currency, saved);
        }
        return saved;
    }

    /** A gateway charge that is live, and the reference it was actually opened under. */
    private record OpenedCharge(String consumerReference, GatewayChargeResponse response) {
    }

    /**
     * Open a gateway charge under the first generation of {@code targetReference} that yields a LIVE
     * charge.
     *
     * <p>The gateway is idempotent on (consumer, consumerReference) and answers a repeat with the
     * charge it already has — <b>whatever its status</b>. So a reference that has been used and since
     * cancelled is spent: asking again returns a cancelled charge, and mirroring that would write an
     * AR row that looks like a collection and collects nothing. AR cannot tell from its own rows
     * which references the gateway has seen, because a rolled-back transaction leaves the gateway a
     * generation ahead — which is exactly what happened on 2026-08-25, when eight repairs adopted the
     * cancelled charges left behind by the previous attempt and reported success.
     *
     * <p>So a spent reference means take the next number, and the numbering stays readable. A charge
     * that came back PAID or PARTIALLY_PAID is a different animal entirely: the gateway holds money
     * against a reference AR has no row for. Opening another charge would collect the same debt
     * twice, so that one stops here for a person to resolve.
     */
    private OpenedCharge openLiveGatewayCharge(
            String targetReference, int firstGeneration, String payerName, BigDecimal amount,
            String vaNumber, String billNumber, String description, Instant expiresAt) {
        for (int generation = firstGeneration;
             generation < firstGeneration + MAX_GENERATION_ATTEMPTS; generation++) {
            String reference = generation <= 1
                    ? targetReference
                    : targetReference + GENERATION_SEPARATOR + generation;
            GatewayChargeResponse response = openGatewayChargeSupersedingVa(
                    reference, payerName, amount, vaNumber, billNumber, description, expiresAt);
            ChargeStatus status = chargeStatus(response.status());
            if (status == ChargeStatus.ACTIVE) {
                return new OpenedCharge(reference, response);
            }
            if (status != ChargeStatus.CANCELLED && status != ChargeStatus.EXPIRED) {
                throw new IllegalStateException(
                        "Gateway already holds a " + status + " charge under reference " + reference
                                + " and AR has no row for it — resolve that before opening another"
                                + " charge for this receivable");
            }
            log.info("Gateway reference {} is spent ({}); taking the next generation", reference, status);
        }
        throw new IllegalStateException(
                "Could not open a live gateway charge for " + targetReference + " within "
                        + MAX_GENERATION_ATTEMPTS + " generations from " + firstGeneration);
    }

    /**
     * Opens a gateway charge for {@code vaNumber}. A VA number points to at most one payable bill,
     * so if the gateway reports the number already active (409), the new bill supersedes the old:
     * cancel the still-collectible prior charge(s) on that number, then create the new one.
     *
     * <p>This mirrors the legacy billing system, which reuses one VA number across successive single
     * bills (its way of doing installments — a 9M bill collected as three 3M bills on the same
     * number, with no installment model). Post-cutover, AR collects an instalment plan through one
     * CLOSED charge whose amount is repriced as each instalment falls due, and never reuses a VA
     * number across charges, so this path stays inert.
     */
    private GatewayChargeResponse openGatewayChargeSupersedingVa(
            String consumerReference, String payerName, BigDecimal amount, String vaNumber,
            String billNumber, String description, Instant expiresAt) {
        GatewayChargeRequest request = new GatewayChargeRequest(
                consumerReference, payerName, ChargeType.CLOSED.name(), amount, expiresAt, billNumber, description,
                java.util.List.of(new GatewayChargeRequest.Account(gatewayProperties.escrowCode(), vaNumber)));
        try {
            return gatewayClient.openCharge(request);
        } catch (HttpClientErrorException.Conflict e) {
            if (!e.getResponseBodyAsString().contains("vaNumber already active")) {
                throw e; // a different conflict (e.g. duplicate reference) — not ours to resolve
            }
            supersedeActiveChargesOnVa(vaNumber);
            return gatewayClient.openCharge(request);
        }
    }

    private void supersedeActiveChargesOnVa(String vaNumber) {
        List<Charge> priors = chargeRepository.findByVaNumberAndStatusIn(
                vaNumber, List.of(ChargeStatus.ACTIVE, ChargeStatus.PARTIALLY_PAID));
        if (priors.isEmpty()) {
            throw new IllegalStateException(
                    "gateway reports VA " + vaNumber + " active but AR has no charge to supersede");
        }
        for (Charge prior : priors) {
            gatewayClient.cancelCharge(prior.getGatewayChargeId());
            // supersede(), not cancel(): this receivable is still owed and must be put back into
            // collection when the number comes free. reopenSupersededReceivables() reads that mark.
            prior.supersede(Instant.now(clock));
            log.info("Superseded charge {} (VA {}) for a reused-VA new bill",
                    prior.getConsumerReference(), vaNumber);
        }
    }

    private static void put(Map<String, String> data, String key, String value) {
        if (value != null && !value.isBlank()) {
            data.put(key, value);
        }
    }

    /** Notify the payer the VA is ready to pay into. New-charge path only (VA now exists). */
    private void notifyBillReady(Invoice receivable, Invoice invoice, Installment installment,
                                 BigDecimal amount, String currency, Charge charge) {
        Debtor debtor = receivable.getDebtor();
        LocalDate dueDate = installment != null ? installment.getDueDate() : invoice.getDueDate();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("debtorName", debtor.getName());
        data.put("invoiceNumber", receivable.getInvoiceNumber());
        data.put("invoiceType", receivable.getInvoiceType().getName());
        data.put("amount", amount.toPlainString());
        data.put("currency", currency);
        data.put("dueDate", dueDate.toString());
        data.put("vaNumber", charge.getVaNumber());
        data.put("escrowCode", charge.getEscrowCode());
        // Carried for hubs whose templates print them; harmless to a hub that ignores them. issueDate
        // in particular is NOT the due date, and the phone travels in the data whether or not SMS is
        // enabled — that gate decides recipients, not what an email may print.
        data.put("issueDate", receivable.getIssueDate().toString());
        put(data, "email", debtor.getEmail());
        put(data, "phone", debtor.getPhone());
        if (receivable.getDescription() != null) {
            data.put("description", receivable.getDescription());
        }
        notificationService.enqueue(notificationProperties.issuedConfig(), debtor.getEmail(), debtor.getPhone(),
                NotificationSourceType.INVOICE_ISSUED, charge.getConsumerReference(),
                payloadMapper.billIssued(data));
    }

    /**
     * Apply a gateway payment webhook. Idempotent on the gateway payment reference. Ambiguous or
     * over-payments are parked (UNAPPLIED) for review — money is recorded, never silently allocated.
     */
    @Transactional
    public CashApplicationResponse applyPayment(GatewayWebhookPayload payload) {
        if ("PAYMENT_REVERSED".equals(payload.eventType())) {
            return reversePayment(payload);
        }
        if ("CHARGE_CANCELLED".equals(payload.eventType())) {
            return applyChargeCancelled(payload);
        }
        if (!isPaymentEvent(payload.eventType())) {
            log.info("Ignoring non-payment webhook event {}", payload.eventType());
            return new CashApplicationResponse(payload.bankReference(), null, BigDecimal.ZERO,
                    "ignored event " + payload.eventType(), null, null, null);
        }
        if (payload.bankReference() == null || payload.bankReference().isBlank()) {
            throw new InvalidRequestException("Webhook missing bankReference (idempotency key)");
        }

        CashApplication existing =
                cashApplicationRepository.findByGatewayPaymentReference(payload.bankReference()).orElse(null);
        if (existing != null) {
            return CashApplicationResponse.from(existing);
        }

        CashApplication application = new CashApplication();
        application.setGatewayPaymentReference(payload.bankReference());
        application.setAmount(payload.paymentAmount());
        application.setReceivedAt(Instant.now(clock));

        Charge charge = chargeRepository.findByConsumerReference(payload.consumerReference()).orElse(null);
        if (charge == null) {
            return park(application, null, "IDR",
                    "no charge for consumerReference " + payload.consumerReference());
        }
        application.setCharge(charge);
        application.setCurrency(charge.getCurrency());

        BigDecimal outstanding = charge.getInvoice() != null
                ? charge.getInvoice().getOutstanding()
                : charge.getInstallment().getOutstanding();

        if (payload.paymentAmount().compareTo(outstanding) > 0) {
            return park(application, charge, charge.getCurrency(),
                    "over-payment: " + payload.paymentAmount() + " exceeds outstanding " + outstanding);
        }

        // Within-outstanding payment: allocate to the target and reduce the receivable.
        boolean plan = charge.getInvoice() != null && charge.getInvoice().isInstallment();
        if (plan) {
            // One VA, one charge, many legs: the payment lands on the earliest unpaid legs.
            for (InvoiceService.PlanAllocation allocation
                    : invoiceService.allocatePlanPayment(charge.getInvoice().getId(), payload.paymentAmount())) {
                CashApplicationLine line = new CashApplicationLine();
                line.setAllocatedAmount(allocation.amount());
                line.setInstallment(allocation.installment());
                application.addLine(line);
            }
        } else {
            CashApplicationLine line = new CashApplicationLine();
            line.setAllocatedAmount(payload.paymentAmount());
            if (charge.getInvoice() != null) {
                invoiceService.applyInvoicePayment(charge.getInvoice().getId(), payload.paymentAmount());
                line.setInvoice(charge.getInvoice());
            } else {
                invoiceService.applyInstallmentPayment(charge.getInstallment().getId(), payload.paymentAmount());
                line.setInstallment(charge.getInstallment());
            }
            application.addLine(line);
        }
        application.setStatus(CashApplicationStatus.APPLIED);

        charge.setCumulativePaid(charge.getCumulativePaid().add(payload.paymentAmount()));
        charge.setStatus(chargeStatus(payload.chargeStatus()));

        Invoice receivable = charge.getInvoice() != null
                ? charge.getInvoice()
                : charge.getInstallment().getSchedule().getInvoice();
        // A paid CLOSED charge is finished at the gateway and its VA retired, but a plan with legs
        // left is not: reopen on the same number for what is due next. No bill announcement — the
        // payer already has the number.
        contractEvents.paymentReceived(charge, application, receivable);
        if (plan && charge.getStatus() == ChargeStatus.PAID && receivable.getOutstanding().signum() > 0) {
            contractEvents.chargeCancelled(charge, receivable, "INSTALLMENT_ROLLOVER");
            openCharge(receivable.getId(), receivable, null, receivable.getSchedule().amountDueOn(today()),
                    receivable.getCurrency(), receivable.getDebtor().getName(), false);
        }
        auditService.record("PAYMENT_APPLIED", "Invoice", receivable.getId(),
                "amount=" + payload.paymentAmount() + " ref=" + payload.bankReference());
        notifyPaymentReceived(charge, application, receivable, payload.paymentAmount());

        return CashApplicationResponse.from(cashApplicationRepository.save(application));
    }

    /**
     * Notify the payer a payment was applied. APPLIED branch only — not on the idempotent replay and
     * not on park (UNAPPLIED is an ops exception, not a customer event). {@code outstanding} is the
     * receivable's balance after applying (0 lets the template say "lunas").
     */
    private void notifyPaymentReceived(Charge charge, CashApplication application,
                                       Invoice receivable, BigDecimal paymentAmount) {
        Debtor debtor = receivable.getDebtor();
        BigDecimal outstanding = charge.getInvoice() != null
                ? charge.getInvoice().getOutstanding()
                : charge.getInstallment().getOutstanding();
        Map<String, String> data = new LinkedHashMap<>();
        data.put("debtorName", debtor.getName());
        data.put("invoiceNumber", receivable.getInvoiceNumber());
        data.put("paymentAmount", paymentAmount.toPlainString());
        data.put("currency", charge.getCurrency());
        data.put("cumulativePaid", charge.getCumulativePaid().toPlainString());
        data.put("outstanding", outstanding.toPlainString());
        data.put("paymentReference", application.getGatewayPaymentReference());
        data.put("paidAt", application.getReceivedAt().toString());
        data.put("paidAtLocal", PAID_AT_LOCAL.format(application.getReceivedAt().atZone(clock.getZone())));
        data.put("invoiceType", receivable.getInvoiceType().getName());
        data.put("invoiceAmount", receivable.getAmount().toPlainString());
        data.put("issueDate", receivable.getIssueDate().toString());
        put(data, "email", debtor.getEmail());
        put(data, "phone", debtor.getPhone());
        notificationService.enqueue(notificationProperties.paymentConfig(), debtor.getEmail(), debtor.getPhone(),
                NotificationSourceType.PAYMENT_RECEIVED, application.getGatewayPaymentReference(),
                payloadMapper.paymentReceived(data));
    }

    /**
     * Reverse a previously applied payment (gateway PAYMENT_REVERSED). Restores the receivable's
     * outstanding, marks the cash application REVERSED, and enqueues a reversing journal. Idempotent:
     * a non-APPLIED application (already reversed / never applied) is a no-op.
     */
    private CashApplicationResponse reversePayment(GatewayWebhookPayload payload) {
        if (payload.bankReference() == null || payload.bankReference().isBlank()) {
            throw new InvalidRequestException("Reversal webhook missing bankReference");
        }
        CashApplication application =
                cashApplicationRepository.findByGatewayPaymentReference(payload.bankReference()).orElse(null);
        if (application == null) {
            log.warn("PAYMENT_REVERSED for unknown payment reference {}", payload.bankReference());
            return new CashApplicationResponse(payload.bankReference(), null, BigDecimal.ZERO,
                    "no payment to reverse for reference " + payload.bankReference(), null, null, null);
        }
        if (application.getStatus() != CashApplicationStatus.APPLIED) {
            return CashApplicationResponse.from(application);
        }

        for (CashApplicationLine line : application.getLines()) {
            if (line.getInvoice() != null) {
                invoiceService.reverseInvoicePayment(line.getInvoice().getId(), line.getAllocatedAmount());
            } else {
                invoiceService.reverseInstallmentPayment(line.getInstallment().getId(), line.getAllocatedAmount());
            }
        }
        application.setStatus(CashApplicationStatus.REVERSED);
        application.setNote("reversed by gateway");

        Charge charge = application.getCharge();
        charge.setCumulativePaid(charge.getCumulativePaid().subtract(application.getAmount()));
        Invoice receivable = charge.getInvoice() != null
                ? charge.getInvoice()
                : charge.getInstallment().getSchedule().getInvoice();
        auditService.record("PAYMENT_REVERSED", "Invoice", receivable.getId(),
                "amount=" + application.getAmount() + " ref=" + payload.bankReference());
        return CashApplicationResponse.from(cashApplicationRepository.save(application));
    }

    private CashApplicationResponse park(CashApplication application, Charge charge, String currency,
                                         String note) {
        application.setCharge(charge);
        application.setCurrency(currency);
        application.setStatus(CashApplicationStatus.UNAPPLIED);
        application.setNote(note);
        log.warn("Parking unapplied payment {}: {}", application.getGatewayPaymentReference(), note);
        return CashApplicationResponse.from(cashApplicationRepository.save(application));
    }

    /**
     * Superseded receivables whose VA number has come free — candidates to put back into collection.
     *
     * <p>Ids only, and each is reopened in its own transaction: one that cannot be repaired must not
     * block the rest, and the list is a snapshot that the reopens themselves invalidate (two bills
     * can be queued on one number).
     *
     * <p>Past-due targets are excluded. Reopening one would mint a charge that expires before the
     * night is out — {@link #expiryFor} lapses a VA the day after the due date — so its deadline is a
     * decision for a person (POST /api/invoices/{id}/due-date), not for a sweep. The count is logged
     * rather than silently dropped.
     */
    @Transactional(readOnly = true)
    public List<String> findSupersededReceivablesToReopen() {
        List<Charge> candidates = chargeRepository.findSupersededWithFreeVa().stream()
                .filter(c -> isCollectible(targetStatusOf(c)))
                .toList();
        Instant now = Instant.now(clock);
        List<String> reopenable = candidates.stream()
                .filter(c -> expiryFor(dueDateOf(c)).isAfter(now))
                .map(Charge::getId)
                .toList();
        int pastDue = candidates.size() - reopenable.size();
        if (pastDue > 0) {
            log.info("{} superseded receivable(s) have a free VA number but are past due —"
                    + " left for a deadline decision, not reopened", pastDue);
        }
        return reopenable;
    }

    /**
     * Put one superseded receivable back into collection on the VA number that has come free.
     *
     * <p>The debt behind a superseded charge was never in question: we retired its collection so a
     * newer bill of the same debtor could use the number legacy insists on reusing. When that newer
     * bill is paid the number is free and this receivable is owed, collectible and — until this
     * existed — invisible: AR OPEN, the billing master AKTIF, the gateway answering NOT_FOUND. It
     * took a cross-database diff to see it at all.
     *
     * <p>Deliberately state-driven rather than triggered by the payment webhook: a sweep re-derives
     * the condition every tick, so a reopen that fails is retried, and one that never ran because the
     * webhook predates this code still happens. It also keeps a failure here away from the payment
     * that revealed it — money already applied must not roll back because a different receivable
     * could not be reopened.
     */
    @Transactional
    public void reopenSupersededReceivable(String chargeId) {
        Charge superseded = chargeRepository.findById(chargeId)
                .orElseThrow(() -> new NotFoundException("Charge not found: " + chargeId));
        if (superseded.getSupersededAt() == null || superseded.getStatus() != ChargeStatus.CANCELLED) {
            return; // already reopened or repaired since the sweep listed it
        }
        // Re-read the number under this transaction: the snapshot may have listed two receivables
        // queued on one VA, and the first reopen has taken it. One ACTIVE charge per number.
        if (!chargeRepository.findByVaNumberAndStatusIn(superseded.getVaNumber(),
                List.of(ChargeStatus.ACTIVE, ChargeStatus.PARTIALLY_PAID)).isEmpty()) {
            return;
        }
        if (!isCollectible(targetStatusOf(superseded))) {
            return; // paid or written off while it sat uncollectible
        }

        Charge reopened = superseded.getInvoice() != null
                ? openCharge(superseded.getInvoice().getId(), superseded.getInvoice(), null,
                        superseded.getInvoice().getOutstanding(), superseded.getInvoice().getCurrency(),
                        superseded.getInvoice().getDebtor().getName())
                : openCharge(superseded.getInstallment().getId(), null, superseded.getInstallment(),
                        superseded.getInstallment().getOutstanding(),
                        superseded.getInstallment().getSchedule().getInvoice().getCurrency(),
                        superseded.getInstallment().getSchedule().getInvoice().getDebtor().getName());

        auditService.record("CHARGE_REOPENED_AFTER_SUPERSESSION", "Charge", reopened.getId(),
                "supersededChargeId=" + superseded.getId()
                        + " supersededAt=" + superseded.getSupersededAt()
                        + " consumerReference=" + reopened.getConsumerReference()
                        + " vaNumber=" + reopened.getVaNumber()
                        + " amount=" + reopened.getAmount());
        log.info("Reopened receivable on freed VA {}: charge {} supersedes-back {}",
                reopened.getVaNumber(), reopened.getConsumerReference(), superseded.getConsumerReference());
    }

    /** The payment status of whatever a charge collects — an invoice, or one installment of one. */
    private static PaymentStatus targetStatusOf(Charge charge) {
        return charge.getInvoice() != null
                ? charge.getInvoice().getPaymentStatus()
                : charge.getInstallment().getPaymentStatus();
    }

    /** The deadline of whatever a charge collects: the installment's own, else the invoice's. */
    private static LocalDate dueDateOf(Charge charge) {
        return charge.getInvoice() != null
                ? charge.getInvoice().getDueDate()
                : charge.getInstallment().getDueDate();
    }

    private static boolean isCollectible(PaymentStatus status) {
        return status == PaymentStatus.OPEN || status == PaymentStatus.PARTIALLY_PAID;
    }

    private static void assertCollectible(PaymentStatus status, String subject) {
        if (status == PaymentStatus.PAID || status == PaymentStatus.WRITTEN_OFF) {
            throw new InvalidRequestException("Cannot open a charge for " + subject + " in status " + status);
        }
    }

    /**
     * The gateway retired this charge's VAs — it is no longer payable. Mirrors that onto our charge
     * row and stops there.
     *
     * <p><b>The invoice is deliberately untouched.</b> The gateway owns collectability, we own the
     * receivable, and a retired VA is not a forgiven debt — that is what soft expiry means. So the
     * invoice stays OPEN and becomes "billed but unpayable", which is a decision for a human
     * (restore a VA, or write it off), not something to infer from a transport event.
     *
     * <p>Without this event we only learn of a cancellation we did not initiate by diffing our
     * database against the gateway's. On 2026-08-18 that was 44 charges, two of them still live and
     * silently uncollectible.
     */
    @Transactional
    public CashApplicationResponse applyChargeCancelled(GatewayWebhookPayload payload) {
        if (payload.chargeId() == null || payload.chargeId().isBlank()) {
            throw new InvalidRequestException("CHARGE_CANCELLED webhook missing chargeId");
        }
        Charge charge = chargeRepository.findByGatewayChargeId(payload.chargeId()).orElse(null);
        if (charge == null) {
            // Legitimate after a manual repair that removed our row (a cancelled charge cannot be
            // reopened through either API, so recovery means deleting and re-opening). Audited rather
            // than thrown: failing here would retry until the delivery died, and the charge is gone
            // either way.
            log.warn("CHARGE_CANCELLED for unknown gateway charge {} — nothing to mirror", payload.chargeId());
            auditService.record("CHARGE_CANCELLED_UNKNOWN", "Charge", payload.chargeId(),
                    "consumerReference=" + payload.consumerReference());
            return new CashApplicationResponse(null, null, BigDecimal.ZERO,
                    "unknown charge " + payload.chargeId(), null, null, null);
        }
        if (charge.getStatus() == ChargeStatus.CANCELLED) {
            return new CashApplicationResponse(null, null, BigDecimal.ZERO,
                    "already cancelled", null, charge.getVaNumber(), null);
        }
        charge.cancel(Instant.now(clock));
        Invoice cancelledOf = charge.getInvoice() != null
                ? charge.getInvoice() : charge.getInstallment().getSchedule().getInvoice();
        contractEvents.chargeCancelled(charge, cancelledOf,
                cancelledOf.getPaymentStatus() == PaymentStatus.WRITTEN_OFF ? "INVOICE_WRITTEN_OFF" : "INVOICE_CANCELLED");
        auditService.record("CHARGE_CANCELLED_MIRRORED", "Charge", charge.getId(),
                "gatewayChargeId=" + charge.getGatewayChargeId()
                        + " consumerReference=" + charge.getConsumerReference()
                        + " vaNumber=" + charge.getVaNumber());
        log.info("Charge {} (VA {}) cancelled at the gateway; receivable left open for review",
                charge.getConsumerReference(), charge.getVaNumber());
        return new CashApplicationResponse(null, null, BigDecimal.ZERO,
                "cancelled", null, charge.getVaNumber(), null);
    }

    private static boolean isPaymentEvent(String eventType) {
        return "PAYMENT_RECEIVED".equals(eventType) || "CHARGE_PAID".equals(eventType);
    }

    private static ChargeStatus chargeStatus(String gatewayStatus) {
        try {
            return ChargeStatus.valueOf(gatewayStatus);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("Unknown gateway charge status: " + gatewayStatus);
        }
    }
}
