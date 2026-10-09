package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.config.ArContractProperties;
import com.artivisi.accountreceivable.dto.CreditNoteRequest;
import com.artivisi.accountreceivable.dto.CreditNoteResponse;
import com.artivisi.accountreceivable.dto.DebtorRequest;
import com.artivisi.accountreceivable.dto.IssueInvoiceRequest;
import com.artivisi.accountreceivable.dto.RecordPaymentRequest;
import com.artivisi.accountreceivable.entity.Charge;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.entity.CreditReason;
import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.entity.DebtorStatus;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.PaymentChannel;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.repository.DebtorRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import com.artivisi.accountreceivable.repository.InvoiceTypeRepository;
import com.artivisi.accountreceivable.service.CollectionService;
import com.artivisi.accountreceivable.service.DebtorService;
import com.artivisi.accountreceivable.service.InvoiceService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Executes one schema-valid command. Each runs in its own transaction: a rejection rolls the
 * whole command back, and the handler then records the rejection separately. Returns the JSON of
 * the event that answers the command, for the idempotency store.
 *
 * <p>Semantic rules the schema cannot express live here, each with its rejection code. Rules that
 * belong to the services (a plan's dates, an invoice's status) are enforced there and surface as
 * {@link InvalidRequestException} carrying a code; a coded refusal is passed through, an uncoded
 * one gets the default for the command.
 */
@Service
public class ContractCommandService {

    private final DebtorRepository debtorRepository;
    private final InvoiceRepository invoiceRepository;
    private final InvoiceTypeRepository invoiceTypeRepository;
    private final ChargeRepository chargeRepository;
    private final InvoiceService invoiceService;
    private final CollectionService collectionService;
    private final DebtorService debtorService;
    private final ContractEventService events;
    private final ArContractProperties properties;

    public ContractCommandService(DebtorRepository debtorRepository, InvoiceRepository invoiceRepository,
                                  InvoiceTypeRepository invoiceTypeRepository, ChargeRepository chargeRepository,
                                  InvoiceService invoiceService, CollectionService collectionService,
                                  DebtorService debtorService, ContractEventService events,
                                  ArContractProperties properties) {
        this.debtorRepository = debtorRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceTypeRepository = invoiceTypeRepository;
        this.chargeRepository = chargeRepository;
        this.invoiceService = invoiceService;
        this.collectionService = collectionService;
        this.debtorService = debtorService;
        this.events = events;
        this.properties = properties;
    }

    /**
     * What a command produced: the event that answers it (null when none does), the key it belongs
     * under, and the topic it was published to. The topic is part of the result because a repeat is
     * answered by republishing the stored event, and an event republished to the wrong topic is
     * worse than none: the sender waits on a topic it will never arrive on.
     */
    public record CommandResult(String messageKey, String resultPayload, String resultTopic) {
    }

    private CommandResult onInvoiceEvent(String messageKey, String resultPayload) {
        return new CommandResult(messageKey, resultPayload, properties.topics().invoiceEvent());
    }

    private CommandResult onPaymentEvent(String messageKey, String resultPayload) {
        return new CommandResult(messageKey, resultPayload, properties.topics().paymentEvent());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CommandResult execute(String type, JsonNode payload, String producer) {
        try {
            return switch (type) {
                case "invoice.requested" -> invoiceRequested(payload);
                case "invoice.amended" -> invoiceAmended(payload);
                case "invoice.planAmended" -> planAmended(payload, producer);
                case "invoice.cancelled" -> invoiceCancelled(payload, producer);
                case "invoice.credited" -> invoiceCredited(payload, producer);
                case "invoice.announcementRequested" -> announcementRequested(payload);
                case "charge.openRequested" -> chargeOpenRequested(payload);
                case "debtor.upserted" -> debtorUpserted(payload);
                case "payment.recorded" -> paymentRecorded(payload);
                default -> throw new ContractRejectedException("SCHEMA_INVALID", "unknown command type " + type);
            };
        } catch (InvalidRequestException e) {
            throw new ContractRejectedException(e.getCode() != null ? e.getCode() : defaultCode(type), e.getMessage());
        }
    }

    private static String defaultCode(String type) {
        return "invoice.requested".equals(type) ? "PLAN_INVALID" : "INVOICE_NOT_AMENDABLE";
    }

    // ------------------------------------------------------------------ commands

    private CommandResult invoiceRequested(JsonNode p) {
        String debtorCode = p.get("debtorCode").asText();
        if (!debtorRepository.existsByCode(debtorCode)) {
            throw new ContractRejectedException("DEBTOR_NOT_FOUND", "debtor " + debtorCode + " is not registered");
        }
        String typeCode = p.get("invoiceTypeCode").asText();
        var type = invoiceTypeRepository.findByCode(typeCode).orElse(null);
        if (type == null || !type.isActive()) {
            throw new ContractRejectedException("INVOICE_TYPE_UNKNOWN", "invoice type " + typeCode + " is not registered");
        }
        BigDecimal amount = new BigDecimal(p.get("amount").asText());
        if (amount.signum() <= 0) {
            throw new ContractRejectedException("AMOUNT_INVALID", "amount must be greater than zero; got " + amount);
        }
        LocalDate issueDate = LocalDate.parse(p.get("issueDate").asText());
        LocalDate dueDate = LocalDate.parse(p.get("dueDate").asText());
        if (dueDate.isBefore(issueDate)) {
            throw new ContractRejectedException("DUE_DATE_INVALID", "dueDate " + dueDate + " precedes issueDate " + issueDate);
        }
        List<IssueInvoiceRequest.InstallmentRequest> plan = plan(p.get("paymentPlan"));
        String description = p.get("description").asText();
        var issued = invoiceService.issue(new IssueInvoiceRequest(
                debtorCode, typeCode, issueDate, dueDate, description,
                List.of(new IssueInvoiceRequest.LineRequest(description, BigDecimal.ONE, amount)),
                plan, null, p.get("idempotencyKey").asText()));
        // issue() announced the invoice (and its plan); the stored answer is that announcement.
        String result = events.lastEvent(debtorCode, "invoice.issued");
        // Absent means open it, so every producer written before this field behaves as it always has.
        // Passing false records the debt now and leaves collection for charge.openRequested — how a
        // payer's whole obligation reaches the books while only one instalment is payable at a time.
        if (!p.hasNonNull("openCharge") || p.get("openCharge").asBoolean()) {
            collectionService.openChargeForInvoice(issued.id());
        }
        return onInvoiceEvent(debtorCode, result);
    }

    private CommandResult invoiceAmended(JsonNode p) {
        Invoice invoice = load(p.get("invoiceNumber").asText());
        assertAmendable(invoice);
        String reference = reference(p);
        if (p.hasNonNull("amount")) {
            collectionService.amendAmount(invoice.getId(), new BigDecimal(p.get("amount").asText()),
                    p.get("reason").asText(), reference);
        }
        if (p.hasNonNull("dueDate")) {
            LocalDate dueDate = LocalDate.parse(p.get("dueDate").asText());
            if (invoice.isInstallment()) {
                var last = invoice.getSchedule().ordered().getLast();
                collectionService.amendInstallmentDueDate(last.getId(), dueDate, reference);
            } else {
                collectionService.amendDueDate(invoice.getId(), dueDate, reference);
            }
        }
        Invoice after = invoiceRepository.findById(invoice.getId()).orElseThrow();
        return onInvoiceEvent(after.getDebtor().getCode(), events.invoiceIssued(after, p.get("idempotencyKey").asText()));
    }

    private CommandResult planAmended(JsonNode p, String producer) {
        Invoice invoice = load(p.get("invoiceNumber").asText());
        assertAmendable(invoice);
        List<IssueInvoiceRequest.InstallmentRequest> legs = plan(p.get("paymentPlan"));
        collectionService.amendPlan(invoice.getId(), legs, p.get("reason").asText(), reference(p), producer,
                p.get("idempotencyKey").asText());
        // planAmended's own event is emitted by the service, on every path.
        return onInvoiceEvent(invoice.getDebtor().getCode(), null);
    }

    private CommandResult invoiceCancelled(JsonNode p, String producer) {
        Invoice invoice = load(p.get("invoiceNumber").asText());
        assertAmendable(invoice);
        String reason = p.get("reason").asText();
        String replacedBy = p.hasNonNull("replacedBy") ? p.get("replacedBy").asText() : null;
        if ("SUPERSEDED".equals(reason)) {
            if (replacedBy == null) {
                throw new ContractRejectedException("REPLACED_BY_REQUIRED", "reason SUPERSEDED needs replacedBy");
            }
            if (resolve(replacedBy).isEmpty()) {
                throw new ContractRejectedException("INVOICE_NOT_FOUND", "replacedBy " + replacedBy + " is not known");
            }
        }
        String note = p.hasNonNull("note") ? p.get("note").asText() : null;
        return onInvoiceEvent(invoice.getDebtor().getCode(),
                invoiceService.cancel(invoice.getId(), reason, replacedBy, note, reference(p), producer));
    }

    /**
     * Record a credit note the upstream decided on: a scholarship someone else settles, a discount,
     * or a correction of a bill that was wrong.
     *
     * <p>Goes through {@link CollectionService#issueCreditNote}, never {@code CreditNoteService},
     * because a bill that now owes nothing must also stop being payable at the bank — otherwise the
     * VA keeps asking for the old amount and gets paid a second time.
     *
     * <p>The reduction is deliberately not an {@code invoice.amended}: an amendment says the price
     * changed, a credit note says the debt was settled without money. Only the second keeps the
     * scholarship visible in what the institution gave away, and only the second leaves reports of
     * cash collected alone.
     */
    private CommandResult invoiceCredited(JsonNode p, String producer) {
        Invoice invoice = load(p.get("invoiceNumber").asText());
        CreditReason reasonCode;
        try {
            reasonCode = CreditReason.valueOf(p.get("reasonCode").asText());
        } catch (IllegalArgumentException e) {
            throw new ContractRejectedException("SCHEMA_INVALID",
                    "reasonCode " + p.get("reasonCode").asText() + " is not one of " + List.of(CreditReason.values()));
        }
        CreditNoteResponse note = collectionService.issueCreditNote(new CreditNoteRequest(
                invoice.getId(), new BigDecimal(p.get("amount").asText()), reasonCode,
                p.hasNonNull("reference") ? p.get("reference").asText() : null,
                p.get("reason").asText()));
        Invoice after = invoiceRepository.findById(invoice.getId()).orElseThrow();
        return onInvoiceEvent(after.getDebtor().getCode(),
                events.invoiceCredited(after, note, p.get("idempotencyKey").asText(), producer));
    }

    /**
     * Open the charge for an invoice issued with {@code openCharge: false}. Idempotent through the
     * charge layer: a second request for an invoice already collecting answers with the charge it
     * has rather than opening a second one.
     */
    private CommandResult chargeOpenRequested(JsonNode p) {
        Invoice invoice = load(p.get("invoiceNumber").asText());
        assertAmendable(invoice);
        collectionService.openDeferredChargeForInvoice(invoice.getId());
        String debtorCode = invoice.getDebtor().getCode();
        return onInvoiceEvent(debtorCode, events.lastEvent(debtorCode, "charge.opened"));
    }

    /**
     * Book a payment the gateway never carried: cash at a counter, a direct transfer, QRIS, a card
     * terminal. The answering event is {@code payment.received} with {@code source: RECORDED} — the
     * same event the bank rail produces, because a campus app raises its applicant's status from
     * that event and from nothing else.
     *
     * <p>It is answered on the payment topic, not the invoice topic, which is why
     * {@link CommandResult} carries the topic. Rejections still arrive as {@code invoice.rejected}
     * on the invoice event topic, as for every other command.
     */
    private CommandResult paymentRecorded(JsonNode p) {
        Invoice invoice = load(p.get("invoiceNumber").asText());
        PaymentChannel channel;
        try {
            channel = PaymentChannel.valueOf(p.get("channel").asText());
        } catch (IllegalArgumentException e) {
            throw new ContractRejectedException("SCHEMA_INVALID", "channel " + p.get("channel").asText()
                    + " is not one of " + List.of(PaymentChannel.values()));
        }
        collectionService.recordPayment(new RecordPaymentRequest(
                invoice.getId(),
                new BigDecimal(p.get("amount").asText()),
                p.get("currency").asText(),
                OffsetDateTime.parse(p.get("paidAt").asText()).toInstant(),
                channel,
                p.get("reference").asText(),
                p.hasNonNull("reason") ? p.get("reason").asText() : null,
                decisionReference(p)));
        String debtorCode = invoice.getDebtor().getCode();
        return onPaymentEvent(debtorCode, events.lastEvent(debtorCode, "payment.received"));
    }

    private CommandResult announcementRequested(JsonNode p) {
        Invoice invoice = load(p.get("invoiceNumber").asText());
        String result = events.invoiceIssued(invoice, null);
        chargeRepository.findByInvoiceId(invoice.getId()).stream()
                .filter(c -> c.getStatus() == ChargeStatus.ACTIVE)
                .findFirst()
                .ifPresent(live -> events.chargeOpened(live, invoice));
        return onInvoiceEvent(invoice.getDebtor().getCode(), result);
    }

    private CommandResult debtorUpserted(JsonNode p) {
        String code = p.get("debtorCode").asText();
        DebtorRequest request = new DebtorRequest(code, p.get("name").asText(),
                p.hasNonNull("email") ? p.get("email").asText() : null,
                p.hasNonNull("phone") ? p.get("phone").asText() : null,
                DebtorStatus.ACTIVE);
        Debtor existing = debtorRepository.findByCode(code).orElse(null);
        if (existing == null) {
            debtorService.create(request);
        } else {
            debtorService.update(existing.getId(), request);
        }
        return onInvoiceEvent(code, null);
    }

    // ------------------------------------------------------------------ helpers

    /**
     * The key a message about this command belongs under: the debtor. Commands that name an
     * invoice rather than a debtor are keyed by that invoice's debtor, so a rejection lands on the
     * same partition as everything else about the same student. Null when neither is known.
     */
    @Transactional(readOnly = true)
    public String messageKeyFor(JsonNode payload) {
        if (payload.hasNonNull("debtorCode")) {
            return payload.get("debtorCode").asText();
        }
        if (payload.hasNonNull("invoiceNumber")) {
            return resolve(payload.get("invoiceNumber").asText())
                    .map(i -> i.getDebtor().getCode()).orElse(null);
        }
        return null;
    }

    /**
     * An invoice answers to either number it has ever been known by: the one AR issued, and the one
     * the campus app was told while the bridge still allocated numbers. An upstream mirror holds
     * whichever was current when the bill reached it, and a command naming the older one is asking
     * about the same debt — rejecting it strands every invoice issued before the 2026-08-27 handoff.
     *
     * <p>Unambiguous by measurement, not by assumption: in production no source number is another
     * invoice's {@code invoiceNumber} and no source number repeats, so at most one invoice matches.
     */
    private java.util.Optional<Invoice> resolve(String number) {
        return invoiceRepository.findByInvoiceNumber(number)
                .or(() -> invoiceRepository.findBySourceBillNumber(number));
    }

    private Invoice load(String invoiceNumber) {
        return resolve(invoiceNumber)
                .orElseThrow(() -> new ContractRejectedException("INVOICE_NOT_FOUND", "invoice " + invoiceNumber + " is not known"));
    }

    /**
     * The upstream approval a person-decided change rests on, for tracing it back to its approval
     * chain. Optional until every producer sends it; absent means the producer did not send one,
     * and is recorded as such rather than filled in.
     */
    private static String reference(JsonNode p) {
        return p.hasNonNull("reference") ? p.get("reference").asText() : null;
    }

    /**
     * The same approval, under its own name. {@code payment.recorded} spends {@code reference} on
     * the receipt number, so the approval behind the payment is a separate field there.
     */
    private static String decisionReference(JsonNode p) {
        return p.hasNonNull("decisionReference") ? p.get("decisionReference").asText() : null;
    }

    private static void assertAmendable(Invoice invoice) {
        if (!invoice.isCollectible() || invoice.getOutstanding().signum() == 0) {
            throw new ContractRejectedException("INVOICE_NOT_AMENDABLE",
                    "invoice " + invoice.getInvoiceNumber() + " is " + invoice.getPaymentStatus());
        }
    }

    private List<IssueInvoiceRequest.InstallmentRequest> plan(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.size() > properties.maxInstallments()) {
            throw new ContractRejectedException("PLAN_INVALID",
                    "a plan may have at most " + properties.maxInstallments() + " installments; got " + node.size());
        }
        List<IssueInvoiceRequest.InstallmentRequest> legs = new ArrayList<>();
        int expected = 1;
        for (JsonNode leg : node) {
            if (leg.get("sequence").asInt() != expected++) {
                throw new ContractRejectedException("PLAN_INVALID", "installment sequence must run 1.." + node.size());
            }
            legs.add(new IssueInvoiceRequest.InstallmentRequest(
                    LocalDate.parse(leg.get("dueDate").asText()), new BigDecimal(leg.get("amount").asText())));
        }
        return legs;
    }
}
