package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArInvoiceProperties;
import com.artivisi.accountreceivable.service.numbering.InvoiceNumberStrategy;
import com.artivisi.accountreceivable.dto.InvoiceListItem;
import com.artivisi.accountreceivable.dto.InvoiceResponse;
import com.artivisi.accountreceivable.dto.InvoiceSummaryResponse;
import com.artivisi.accountreceivable.dto.IssueInvoiceRequest;
import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.entity.Installment;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.InvoiceLine;
import com.artivisi.accountreceivable.entity.InvoiceType;
import com.artivisi.accountreceivable.entity.PaymentSchedule;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.repository.DebtorRepository;
import com.artivisi.accountreceivable.repository.InstallmentRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import com.artivisi.accountreceivable.repository.InvoiceTypeRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;

@Service
public class InvoiceService {

    /** Single supported currency for v1. The column exists so multi-currency is a later addition. */
    private static final String CURRENCY = "IDR";

    /** Money is always scale 2 so values serialize consistently (0.00, not 0). */
    private static final BigDecimal MONEY_ZERO = BigDecimal.ZERO.setScale(2);

    private final InvoiceRepository invoiceRepository;
    private final InstallmentRepository installmentRepository;
    private final DebtorRepository debtorRepository;
    private final InvoiceTypeRepository invoiceTypeRepository;
    private final RunningNumberService runningNumberService;
    private final ChargeCancellationService chargeCancellationService;
    private final AuditService auditService;
    private final ArInvoiceProperties invoiceProperties;
    private final InvoiceNumberStrategy invoiceNumberStrategy;
    private final Clock clock;
    private final com.artivisi.accountreceivable.contract.ContractEventService contractEvents;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          InstallmentRepository installmentRepository,
                          DebtorRepository debtorRepository,
                          InvoiceTypeRepository invoiceTypeRepository,
                          RunningNumberService runningNumberService,
                          ChargeCancellationService chargeCancellationService,
                          AuditService auditService,
                          ArInvoiceProperties invoiceProperties,
                          InvoiceNumberStrategy invoiceNumberStrategy,
                          com.artivisi.accountreceivable.contract.ContractEventService contractEvents, Clock clock) {
        this.invoiceRepository = invoiceRepository;
        this.installmentRepository = installmentRepository;
        this.debtorRepository = debtorRepository;
        this.invoiceTypeRepository = invoiceTypeRepository;
        this.runningNumberService = runningNumberService;
        this.chargeCancellationService = chargeCancellationService;
        this.auditService = auditService;
        this.invoiceProperties = invoiceProperties;
        this.invoiceNumberStrategy = invoiceNumberStrategy;
        this.clock = clock;
        this.contractEvents = contractEvents;
    }

    @Transactional
    public InvoiceResponse issue(IssueInvoiceRequest request) {
        // dueDate may precede issueDate: a late-issued receivable is simply overdue at issuance
        // (overdue is derived from dueDate, never stored), so no ordering constraint applies.
        Debtor debtor = debtorRepository.findByCode(request.debtorCode())
                .orElseThrow(() -> new InvalidRequestException("DEBTOR_NOT_FOUND", "Unknown debtor code: " + request.debtorCode()));
        InvoiceType type = invoiceTypeRepository.findByCode(request.invoiceTypeCode())
                .orElseThrow(() -> new InvalidRequestException("INVOICE_TYPE_UNKNOWN", "Unknown invoice type code: " + request.invoiceTypeCode()));
        if (!type.isActive()) {
            throw new InvalidRequestException("INVOICE_TYPE_UNKNOWN", "Invoice type is not active: " + request.invoiceTypeCode());
        }

        Invoice invoice = new Invoice();
        BigDecimal amount = BigDecimal.ZERO;
        int lineNo = 1;
        for (IssueInvoiceRequest.LineRequest lr : request.lines()) {
            BigDecimal lineAmount = exactMoney(lr.quantity().multiply(lr.unitAmount()),
                    "line " + lineNo + " amount");
            InvoiceLine line = new InvoiceLine();
            line.setLineNo(lineNo++);
            line.setDescription(lr.description());
            line.setQuantity(lr.quantity());
            line.setUnitAmount(lr.unitAmount());
            line.setLineAmount(lineAmount);
            invoice.addLine(line);
            amount = amount.add(lineAmount);
        }
        if (amount.signum() <= 0) {
            throw new InvalidRequestException("AMOUNT_INVALID", "Invoice amount must be positive");
        }

        invoice.setInvoiceNumber(invoiceNumberStrategy.next(type, request.issueDate()));
        invoice.setDebtor(debtor);
        invoice.setInvoiceType(type);
        invoice.setIssueDate(request.issueDate());
        invoice.setDueDate(request.dueDate());
        invoice.setCurrency(CURRENCY);
        invoice.setAmount(amount);
        invoice.setOutstanding(amount);
        invoice.setPaymentStatus(PaymentStatus.OPEN);
        invoice.setDescription(request.description());
        invoice.setSourceBillNumber(request.sourceBillNumber());

        List<IssueInvoiceRequest.InstallmentRequest> installments = request.installments();
        if (installments != null && !installments.isEmpty()) {
            invoice.setInstallment(true);
            attachSchedule(invoice, installments, amount);
        } else {
            invoice.setInstallment(false);
        }

        invoice.recomputeEarliestUnpaidDueDate();
        Invoice saved = invoiceRepository.save(invoice);
        auditService.record("INVOICE_ISSUED", "Invoice", saved.getId(),
                saved.getInvoiceNumber() + " amount=" + saved.getAmount());
        // Announced from here, whichever door the invoice came in by — the contract, the REST API, the
        // admin form — so an upstream's mirror never depends on which one was used.
        contractEvents.invoiceIssued(saved, request.correlationId());
        if (saved.isInstallment()) {
            contractEvents.planAmended(saved, request.correlationId(), "ISSUED", "ar");
        }
        return InvoiceResponse.from(saved, today());
    }

    private void attachSchedule(Invoice invoice, List<IssueInvoiceRequest.InstallmentRequest> requests,
                                BigDecimal invoiceAmount) {
        PaymentSchedule schedule = new PaymentSchedule();
        schedule.setInvoice(invoice);
        schedule.setInstallmentCount(requests.size());
        BigDecimal total = BigDecimal.ZERO;
        int sequence = 1;
        for (IssueInvoiceRequest.InstallmentRequest ir : requests) {
            BigDecimal amount = exactMoney(ir.amount(), "installment " + sequence + " amount");
            Installment installment = new Installment();
            installment.setSequence(sequence++);
            installment.setDueDate(ir.dueDate());
            installment.setAmount(amount);
            installment.setOutstanding(amount);
            installment.setPaymentStatus(PaymentStatus.OPEN);
            schedule.addInstallment(installment);
            total = total.add(amount);
        }
        if (total.compareTo(invoiceAmount) != 0) {
            throw new InvalidRequestException("PLAN_INVALID",
                    "Installment amounts (" + total + ") must sum to invoice amount (" + invoiceAmount + ")");
        }
        assertDatesAscend(schedule.getInstallments());
        // The plan's VA expires with the invoice, and rollover never moves expiry, so the invoice's
        // date has to be the last installment's — otherwise the VA lapses with legs still to pay.
        if (!schedule.lastDueDate().equals(invoice.getDueDate())) {
            throw new InvalidRequestException("PLAN_INVALID", "Invoice dueDate (" + invoice.getDueDate()
                    + ") must equal the last installment's dueDate (" + schedule.lastDueDate() + ")");
        }
        invoice.setSchedule(schedule);
    }

    private static void assertDatesAscend(List<Installment> legs) {
        LocalDate previous = null;
        for (Installment leg : legs) {
            if (previous != null && !leg.getDueDate().isAfter(previous)) {
                throw new InvalidRequestException("PLAN_INVALID", "Installment " + leg.getSequence() + " (" + leg.getDueDate()
                        + ") must fall due after the previous installment (" + previous + ")");
            }
            previous = leg.getDueDate();
        }
    }

    /** One installment's share of a payment against a plan. */
    public record PlanAllocation(Installment installment, BigDecimal amount) {
    }

    /**
     * Apply a payment received on a plan's single VA to its installments, earliest due first.
     *
     * <p>The VA answers the sum of every installment that has fallen due plus the next one, so a
     * payment normally settles exactly those legs. The allocation is still done leg by leg rather
     * than assumed, because a manual receipt may have part-paid a leg in between.
     */
    @Transactional
    public List<PlanAllocation> allocatePlanPayment(String invoiceId, BigDecimal amount) {
        Invoice invoice = load(invoiceId);
        if (!invoice.isInstallment()) {
            throw new InvalidRequestException("Not an installment invoice: " + invoice.getInvoiceNumber());
        }
        assertPayable(invoice.getPaymentStatus(), "invoice " + invoice.getInvoiceNumber());
        List<PlanAllocation> allocations = new java.util.ArrayList<>();
        BigDecimal remaining = amount;
        for (Installment leg : invoice.getSchedule().ordered()) {
            if (remaining.signum() == 0) {
                break;
            }
            if (leg.getOutstanding().signum() == 0) {
                continue;
            }
            BigDecimal take = remaining.min(leg.getOutstanding());
            leg.setOutstanding(leg.getOutstanding().subtract(take));
            leg.setPaymentStatus(statusFor(leg.getOutstanding(), leg.getAmount()));
            allocations.add(new PlanAllocation(leg, take));
            remaining = remaining.subtract(take);
        }
        if (remaining.signum() > 0) {
            throw new InvalidRequestException("Payment " + amount + " exceeds outstanding on invoice "
                    + invoice.getInvoiceNumber());
        }
        rollUpSchedule(invoice);
        return allocations;
    }

    /**
     * Replace the unpaid part of a plan with a new one. Paid installments stay exactly as they are;
     * the new legs must sum to what is still outstanding, so the debt itself never changes here —
     * only when it is due.
     *
     * <p>Refused while any installment is part-paid: a manual receipt that settled half a leg
     * cannot be carried into a new plan without deciding which new leg it belongs to, and that is a
     * decision for a person, made by reversing or completing the receipt first.
     */
    @Transactional
    public Invoice amendPlan(String invoiceId, List<IssueInvoiceRequest.InstallmentRequest> legs, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("PLAN_INVALID", "A plan amendment reason is required");
        }
        if (legs == null || legs.isEmpty()) {
            throw new InvalidRequestException("PLAN_INVALID", "A plan needs at least one installment");
        }
        Invoice invoice = load(invoiceId);
        if (!invoice.isCollectible() || invoice.getOutstanding().signum() == 0) {
            throw new InvalidRequestException("INVOICE_NOT_AMENDABLE", "Cannot amend the plan of invoice " + invoice.getInvoiceNumber()
                    + " in status " + invoice.getPaymentStatus());
        }
        PaymentSchedule schedule = invoice.getSchedule();
        if (schedule == null) {
            schedule = new PaymentSchedule();
            schedule.setInvoice(invoice);
            invoice.setSchedule(schedule);
            invoice.setInstallment(true);
        }
        String previousPlan = schedule.getInstallments().stream()
                .map(i -> i.getSequence() + ":" + i.getDueDate() + ":" + i.getAmount() + ":" + i.getPaymentStatus())
                .reduce((a, b) -> a + "," + b).orElse("-");
        for (Installment leg : schedule.getInstallments()) {
            if (leg.getPaymentStatus() == PaymentStatus.PARTIALLY_PAID) {
                throw new InvalidRequestException("PLAN_INVALID", "Installment " + leg.getSequence()
                        + " is part-paid; complete or reverse that receipt before amending the plan");
            }
        }
        List<Installment> kept = new java.util.ArrayList<>(schedule.getInstallments().stream()
                .filter(i -> i.getPaymentStatus() == PaymentStatus.PAID)
                .sorted(java.util.Comparator.comparing(Installment::getSequence))
                .toList());
        List<Installment> replaced = schedule.getInstallments().stream()
                .filter(i -> i.getPaymentStatus() != PaymentStatus.PAID).toList();
        schedule.getInstallments().removeAll(replaced);
        // Delete and flush before the new legs are inserted: Hibernate orders inserts before
        // orphan deletes, and a new leg would collide with the old one on (schedule, sequence).
        installmentRepository.deleteAll(replaced);
        installmentRepository.flush();

        LocalDate today = today();
        BigDecimal total = BigDecimal.ZERO;
        int sequence = kept.size() + 1;
        for (IssueInvoiceRequest.InstallmentRequest lr : legs) {
            if (sequence == kept.size() + 1 && lr.dueDate().isBefore(today)) {
                throw new InvalidRequestException("PLAN_INVALID", "The first new installment (" + lr.dueDate()
                        + ") may not fall due before today");
            }
            BigDecimal amount = exactMoney(lr.amount(), "installment " + sequence + " amount");
            Installment leg = new Installment();
            leg.setSequence(sequence++);
            leg.setDueDate(lr.dueDate());
            leg.setAmount(amount);
            leg.setOutstanding(amount);
            leg.setPaymentStatus(PaymentStatus.OPEN);
            schedule.addInstallment(leg);
            total = total.add(amount);
        }
        if (total.compareTo(invoice.getOutstanding()) != 0) {
            throw new InvalidRequestException("PLAN_INVALID", "New installments (" + total + ") must sum to the outstanding amount ("
                    + invoice.getOutstanding() + ")");
        }
        assertDatesAscend(schedule.getInstallments().stream()
                .filter(i -> i.getPaymentStatus() != PaymentStatus.PAID).toList());
        schedule.setInstallmentCount(schedule.getInstallments().size());
        invoice.setDueDate(schedule.lastDueDate());
        rollUpSchedule(invoice);
        auditService.record("INVOICE_PLAN_AMENDED", "Invoice", invoice.getId(),
                invoice.getInvoiceNumber() + " reason=" + reason + " previous=[" + previousPlan + "]");
        return invoice;
    }

    /**
     * Withdraw an invoice that should never have been collectible: issued in error, duplicated, or
     * replaced by another after a correction. The invoice is treated as never having been a debt —
     * it leaves aging and provisioning entirely — which is what separates it from a write-off.
     * Its charges are cancelled at the gateway through the same outbox a write-off uses.
     */
    @Transactional
    public String cancel(String invoiceId, String reason, String replacedBy, String note, String decidedBy) {
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("INVOICE_NOT_AMENDABLE", "A cancellation reason is required");
        }
        Invoice invoice = load(invoiceId);
        if (!invoice.isCollectible() || invoice.getPaymentStatus() == PaymentStatus.PAID) {
            throw new InvalidRequestException("INVOICE_NOT_AMENDABLE",
                    "Cannot cancel invoice " + invoice.getInvoiceNumber() + " in status " + invoice.getPaymentStatus());
        }
        String event = contractEvents.invoiceCancelled(invoice, reason, replacedBy, decidedBy);
        invoice.setPaymentStatus(PaymentStatus.CANCELLED);
        if (invoice.isInstallment()) {
            for (Installment leg : invoice.getSchedule().getInstallments()) {
                if (leg.getPaymentStatus() != PaymentStatus.PAID) {
                    leg.setPaymentStatus(PaymentStatus.CANCELLED);
                }
            }
        }
        invoice.recomputeEarliestUnpaidDueDate();
        chargeCancellationService.enqueueForWriteOff(invoice);
        auditService.record("INVOICE_CANCELLED", "Invoice", invoice.getId(),
                invoice.getInvoiceNumber() + " reason=" + reason
                        + (replacedBy != null ? " replacedBy=" + replacedBy : "")
                        + (note != null ? " note=" + note : "") + " by=" + decidedBy);
        return event;
    }

    /**
     * Correct what an invoice is worth. The debt is what the new figure says it always was; a
     * reduction is how a scholarship or discount decided after issue reaches the books. Reducing to
     * zero cancels the invoice — nothing was ever received, so PAID would be a lie.
     *
     * <p>On a plan the difference lands on the last unpaid installment, so earlier legs the payer
     * has already been told about keep their figures.
     */
    @Transactional
    public Invoice amendAmount(String invoiceId, BigDecimal newAmount, String reason) {
        Invoice invoice = load(invoiceId);
        if (!invoice.isCollectible() || invoice.getOutstanding().signum() == 0) {
            throw new InvalidRequestException("INVOICE_NOT_AMENDABLE",
                    "Cannot amend invoice " + invoice.getInvoiceNumber() + " in status " + invoice.getPaymentStatus());
        }
        BigDecimal amount = exactMoney(newAmount, "amount");
        BigDecimal paid = invoice.getAmount().subtract(invoice.getOutstanding());
        if (amount.compareTo(paid) < 0) {
            throw new InvalidRequestException("AMOUNT_INVALID", "New amount " + amount
                    + " is below what has already been paid (" + paid + ") on " + invoice.getInvoiceNumber());
        }
        if (amount.signum() == 0) {
            cancel(invoiceId, "AMENDED_TO_ZERO", null, reason, "ar");
            return invoice;
        }
        BigDecimal previous = invoice.getAmount();
        BigDecimal delta = amount.subtract(previous);
        if (invoice.isInstallment()) {
            Installment last = invoice.getSchedule().ordered().stream()
                    .filter(i -> i.getOutstanding().signum() > 0)
                    .reduce((a, b) -> b).orElseThrow();
            BigDecimal legAmount = last.getAmount().add(delta);
            if (legAmount.signum() <= 0) {
                throw new InvalidRequestException("PLAN_INVALID", "Reducing by " + delta.negate()
                        + " leaves the last installment with nothing; amend the plan instead");
            }
            last.setAmount(legAmount);
            last.setOutstanding(last.getOutstanding().add(delta));
            invoice.setAmount(amount);
            rollUpSchedule(invoice);
        } else {
            invoice.setAmount(amount);
            invoice.setOutstanding(invoice.getOutstanding().add(delta));
            invoice.setPaymentStatus(statusFor(invoice.getOutstanding(), invoice.getAmount()));
            invoice.recomputeEarliestUnpaidDueDate();
        }
        auditService.record("INVOICE_AMOUNT_AMENDED", "Invoice", invoice.getId(),
                invoice.getInvoiceNumber() + " from=" + previous + " to=" + amount + " reason=" + reason);
        return invoice;
    }

    private void rollUpSchedule(Invoice invoice) {
        BigDecimal outstanding = invoice.getSchedule().getInstallments().stream()
                .map(Installment::getOutstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        invoice.setOutstanding(outstanding);
        invoice.setPaymentStatus(statusFor(outstanding, invoice.getAmount()));
        invoice.recomputeEarliestUnpaidDueDate();
    }

    @Transactional(readOnly = true)
    public InvoiceResponse get(String id) {
        return InvoiceResponse.from(load(id), today());
    }

    /**
     * Paginated invoice-list search — filtered, sorted, and limited in the database (no full-table
     * load). {@code status} is a {@link PaymentStatus} name, or the special {@code "MENUNGGAK"} meaning
     * overdue (derived in SQL from the denormalized {@code earliestUnpaidDueDate} column, cutting across
     * OPEN/PARTIALLY_PAID). {@code typeCode} matches the invoice-type code; {@code q} matches invoice
     * number or debtor code (case-insensitive). All three are nullable/blank-safe. An unrecognized
     * (non-blank, non-MENUNGGAK) status fails loud rather than silently matching nothing.
     */
    @Transactional(readOnly = true)
    public Page<InvoiceListItem> searchPage(String status, String typeCode, String q, Pageable pageable) {
        return searchPage(status, typeCode, q, null, pageable);
    }

    /**
     * As {@link #searchPage(String, String, String, Pageable)}, plus an aging band so the provision
     * report's cells can be opened. The band is translated here rather than in the query so the two
     * always agree on where a boundary falls — an off-by-one would make a total and its detail differ.
     */
    @Transactional(readOnly = true)
    public Page<InvoiceListItem> searchPage(String status, String typeCode, String q, String bucket,
                                            Pageable pageable) {
        boolean overdueOnly = "MENUNGGAK".equals(status);
        PaymentStatus statusFilter = null;
        if (!overdueOnly && status != null && !status.isBlank()) {
            try {
                statusFilter = PaymentStatus.valueOf(status);
            } catch (IllegalArgumentException e) {
                throw new InvalidRequestException("Unknown invoice status filter: " + status);
            }
        }
        String typeFilter = (typeCode == null || typeCode.isBlank()) ? null : typeCode;
        String qFilter = (q == null || q.isBlank()) ? null : "%" + q.trim().toLowerCase() + "%";
        LocalDate today = today();
        LocalDate bucketFrom = null;   // dueDate <= this  => at least this overdue
        LocalDate bucketTo = null;     // dueDate >= this  => no more overdue than this
        if (bucket != null && !bucket.isBlank()) {
            switch (bucket) {
                case "CURRENT" -> bucketTo = today;                       // not yet due
                case "DUE_1_30" -> { bucketFrom = today.minusDays(1); bucketTo = today.minusDays(30); }
                case "DUE_31_60" -> { bucketFrom = today.minusDays(31); bucketTo = today.minusDays(60); }
                case "DUE_61_90" -> { bucketFrom = today.minusDays(61); bucketTo = today.minusDays(90); }
                case "DUE_90_PLUS" -> bucketFrom = today.minusDays(91);
                default -> throw new InvalidRequestException("Unknown aging bucket: " + bucket);
            }
        }
        return invoiceRepository.search(overdueOnly, statusFilter, typeFilter, qFilter,
                bucketFrom, bucketTo, today, pageable);
    }

    /** Same paginated search as {@link #searchPage}, mapped to the public API summary shape. */
    @Transactional(readOnly = true)
    public Page<InvoiceSummaryResponse> searchSummaryPage(String status, String typeCode, String q,
                                                          Pageable pageable) {
        LocalDate today = today();
        return searchPage(status, typeCode, q, pageable).map(i -> InvoiceSummaryResponse.from(i, today));
    }

    @Transactional
    public InvoiceResponse applyInvoicePayment(String invoiceId, BigDecimal amount) {
        Invoice invoice = load(invoiceId);
        if (invoice.isInstallment()) {
            throw new InvalidRequestException("Installment invoice: apply payment to an installment");
        }
        assertPayable(invoice.getPaymentStatus(), "invoice " + invoice.getInvoiceNumber());
        invoice.setOutstanding(reduce(invoice.getOutstanding(), amount, "invoice " + invoice.getInvoiceNumber()));
        invoice.setPaymentStatus(statusFor(invoice.getOutstanding(), invoice.getAmount()));
        invoice.recomputeEarliestUnpaidDueDate();
        return InvoiceResponse.from(invoice, today());
    }

    @Transactional
    public InvoiceResponse applyInstallmentPayment(String installmentId, BigDecimal amount) {
        Installment installment = installmentRepository.findById(installmentId)
                .orElseThrow(() -> new NotFoundException("Installment not found: " + installmentId));
        assertPayable(installment.getPaymentStatus(), "installment " + installmentId);
        installment.setOutstanding(reduce(installment.getOutstanding(), amount, "installment " + installmentId));
        installment.setPaymentStatus(statusFor(installment.getOutstanding(), installment.getAmount()));

        Invoice invoice = installment.getSchedule().getInvoice();
        BigDecimal outstanding = invoice.getSchedule().getInstallments().stream()
                .map(Installment::getOutstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        invoice.setOutstanding(outstanding);
        invoice.setPaymentStatus(statusFor(outstanding, invoice.getAmount()));
        invoice.recomputeEarliestUnpaidDueDate();
        return InvoiceResponse.from(invoice, today());
    }

    /** Restore outstanding after a reversed payment on a single-payment invoice. */
    @Transactional
    public void reverseInvoicePayment(String invoiceId, BigDecimal amount) {
        Invoice invoice = load(invoiceId);
        BigDecimal restored = invoice.getOutstanding().add(amount);
        if (restored.compareTo(invoice.getAmount()) > 0) {
            throw new InvalidRequestException(
                    "Reversal " + amount + " exceeds invoice amount on " + invoice.getInvoiceNumber());
        }
        invoice.setOutstanding(restored);
        invoice.setPaymentStatus(statusFor(restored, invoice.getAmount()));
        invoice.recomputeEarliestUnpaidDueDate();
    }

    /** Restore outstanding after a reversed payment on an installment, rolling up to the invoice. */
    @Transactional
    public void reverseInstallmentPayment(String installmentId, BigDecimal amount) {
        Installment installment = installmentRepository.findById(installmentId)
                .orElseThrow(() -> new NotFoundException("Installment not found: " + installmentId));
        BigDecimal restored = installment.getOutstanding().add(amount);
        if (restored.compareTo(installment.getAmount()) > 0) {
            throw new InvalidRequestException("Reversal " + amount + " exceeds installment amount");
        }
        installment.setOutstanding(restored);
        installment.setPaymentStatus(statusFor(restored, installment.getAmount()));

        Invoice invoice = installment.getSchedule().getInvoice();
        BigDecimal outstanding = invoice.getSchedule().getInstallments().stream()
                .map(Installment::getOutstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        invoice.setOutstanding(outstanding);
        invoice.setPaymentStatus(statusFor(outstanding, invoice.getAmount()));
        invoice.recomputeEarliestUnpaidDueDate();
    }

    @Transactional
    public InvoiceResponse writeOff(String invoiceId, String reason) {
        // Forgiving a debt is a judgement, and a judgement nobody recorded cannot be reviewed later.
        // Write-off is irreversible here (a WRITTEN_OFF invoice cannot be written off again, and
        // there is no un-write-off), so the why is the only thing a future reader will have.
        if (reason == null || reason.isBlank()) {
            throw new InvalidRequestException("A write-off reason is required");
        }
        Invoice invoice = load(invoiceId);
        if (invoice.getPaymentStatus() == PaymentStatus.PAID
                || invoice.getPaymentStatus() == PaymentStatus.WRITTEN_OFF
                || invoice.getPaymentStatus() == PaymentStatus.CANCELLED) {
            throw new InvalidRequestException(
                    "Cannot write off invoice in status " + invoice.getPaymentStatus());
        }
        BigDecimal writtenOff = invoice.getOutstanding();
        if (invoice.isInstallment()) {
            for (Installment i : invoice.getSchedule().getInstallments()) {
                if (i.getPaymentStatus() != PaymentStatus.PAID) {
                    i.setOutstanding(MONEY_ZERO);
                    i.setPaymentStatus(PaymentStatus.WRITTEN_OFF);
                }
            }
        }
        invoice.setOutstanding(MONEY_ZERO);
        invoice.setPaymentStatus(PaymentStatus.WRITTEN_OFF);
        invoice.recomputeEarliestUnpaidDueDate();
        chargeCancellationService.enqueueForWriteOff(invoice);
        auditService.record("INVOICE_WRITTEN_OFF", "Invoice", invoice.getId(),
                invoice.getInvoiceNumber() + " amount=" + writtenOff + " reason=" + reason.strip());
        contractEvents.invoiceWrittenOff(invoice, reason, "ar-admin");
        return InvoiceResponse.from(invoice, today());
    }

    Invoice load(String id) {
        return invoiceRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + id));
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }

    private static void assertPayable(PaymentStatus status, String subject) {
        if (status == PaymentStatus.PAID || status == PaymentStatus.WRITTEN_OFF
                || status == PaymentStatus.CANCELLED) {
            throw new InvalidRequestException("Cannot pay " + subject + " in status " + status);
        }
    }

    private static BigDecimal reduce(BigDecimal outstanding, BigDecimal payment, String subject) {
        BigDecimal amount = exactMoney(payment, "payment amount");
        if (amount.compareTo(outstanding) > 0) {
            throw new InvalidRequestException(
                    "Payment " + amount + " exceeds outstanding " + outstanding + " on " + subject);
        }
        return outstanding.subtract(amount);
    }

    private static PaymentStatus statusFor(BigDecimal outstanding, BigDecimal amount) {
        if (outstanding.signum() == 0) {
            return PaymentStatus.PAID;
        }
        return outstanding.compareTo(amount) < 0 ? PaymentStatus.PARTIALLY_PAID : PaymentStatus.OPEN;
    }

    /** Reject sub-cent precision rather than silently rounding money. */
    private static BigDecimal exactMoney(BigDecimal value, String subject) {
        if (value.stripTrailingZeros().scale() > 2) {
            throw new InvalidRequestException(subject + " has sub-cent precision: " + value);
        }
        return value.setScale(2);
    }
}
