package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.config.ArContractProperties;
import com.artivisi.accountreceivable.dto.CreditNoteResponse;
import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.Charge;
import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.entity.ContractOutboxStatus;
import com.artivisi.accountreceivable.entity.Installment;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.repository.ContractEventOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Builds and enqueues the events of the v2 contract. Every method is called from inside the
 * transaction that makes the change, so the event and the change commit or roll back together.
 * Emitted on every path — the upstream-initiated one and the admin-initiated one alike — because
 * an upstream's mirror of a receivable is derived from these events and nothing else.
 *
 * <p>With the contract disabled nothing is recorded: a deployment without an upstream on a broker
 * would otherwise accumulate an outbox nobody drains.
 */
@Service
public class ContractEventService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ContractEventOutboxRepository outboxRepository;
    private final ArContractProperties properties;
    private final Clock clock;

    public ContractEventService(ContractEventOutboxRepository outboxRepository,
                                ArContractProperties properties, Clock clock) {
        this.outboxRepository = outboxRepository;
        this.properties = properties;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ invoice events

    public String invoiceIssued(Invoice invoice, String correlationId) {
        ObjectNode p = JSON.createObjectNode();
        putNullable(p, "correlationId", correlationId);
        putInvoiceNumbers(p, invoice);
        p.put("debtorCode", invoice.getDebtor().getCode());
        p.put("invoiceTypeCode", invoice.getInvoiceType().getCode());
        p.put("currency", invoice.getCurrency());
        p.put("amount", money(invoice.getAmount()));
        p.put("issueDate", invoice.getIssueDate().toString());
        p.put("dueDate", invoice.getDueDate().toString());
        p.put("description", invoice.getDescription() == null ? "" : invoice.getDescription());
        return enqueue(properties.topics().invoiceEvent(), invoice.getDebtor().getCode(), "invoice.issued", p);
    }

    public String invoiceRejected(String debtorCode, String correlationId, String code, String reason) {
        ObjectNode p = JSON.createObjectNode();
        p.put("correlationId", correlationId);
        p.put("code", code);
        p.put("reason", reason);
        return enqueue(properties.topics().invoiceEvent(), debtorCode == null ? correlationId : debtorCode,
                "invoice.rejected", p);
    }

    public String planAmended(Invoice invoice, String correlationId, String reason, String decidedBy) {
        ObjectNode p = JSON.createObjectNode();
        putNullable(p, "correlationId", correlationId);
        putInvoiceNumbers(p, invoice);
        p.put("debtorCode", invoice.getDebtor().getCode());
        p.put("amount", money(invoice.getAmount()));
        p.put("cumulativePaid", money(invoice.getAmount().subtract(invoice.getOutstanding())));
        p.put("outstanding", money(invoice.getOutstanding()));
        ArrayNode plan = p.putArray("paymentPlan");
        for (Installment leg : invoice.getSchedule().ordered()) {
            ObjectNode l = plan.addObject();
            l.put("sequence", leg.getSequence());
            l.put("dueDate", leg.getDueDate().toString());
            l.put("amount", money(leg.getAmount()));
            l.put("status", leg.getPaymentStatus().name());
        }
        p.put("reason", reason);
        p.put("decidedBy", decidedBy);
        p.put("decidedAt", now());
        return enqueue(properties.topics().invoiceEvent(), invoice.getDebtor().getCode(), "invoice.planAmended", p);
    }

    public String invoiceCancelled(Invoice invoice, String reason, String replacedBy, String decidedBy) {
        ObjectNode p = disposition(invoice, reason, decidedBy);
        putNullable(p, "replacedBy", replacedBy);
        return enqueue(properties.topics().invoiceEvent(), invoice.getDebtor().getCode(), "invoice.cancelled", p);
    }

    /**
     * A credit note the upstream asked for, answered on the event topic.
     *
     * <p>Carries what the invoice is worth <em>after</em> the credit, because that is the whole point
     * of the message: the sender has to stop showing the payer an amount nobody will collect. The
     * invoice's own amount is unchanged and reported as {@code invoiceAmount} — a credit note never
     * edits an issued figure, it stands beside it.
     */
    public String invoiceCredited(Invoice invoice, CreditNoteResponse note, String correlationId,
                                  String decidedBy) {
        ObjectNode p = JSON.createObjectNode();
        putNullable(p, "correlationId", correlationId);
        p.put("creditNoteNumber", note.creditNoteNumber());
        putInvoiceNumbers(p, invoice);
        p.put("debtorCode", invoice.getDebtor().getCode());
        p.put("amount", money(note.amount()));
        p.put("invoiceAmount", money(invoice.getAmount()));
        p.put("outstanding", money(invoice.getOutstanding()));
        p.put("invoiceStatus", invoice.getPaymentStatus().name());
        p.put("reasonCode", note.reasonCode().name());
        putNullable(p, "reference", note.reference());
        putNullable(p, "reason", note.reason());
        p.put("decidedBy", decidedBy);
        p.put("decidedAt", now());
        return enqueue(properties.topics().invoiceEvent(), invoice.getDebtor().getCode(), "invoice.credited", p);
    }

    public String invoiceWrittenOff(Invoice invoice, String reason, String decidedBy) {
        ObjectNode p = disposition(invoice, reason, decidedBy);
        return enqueue(properties.topics().invoiceEvent(), invoice.getDebtor().getCode(), "invoice.writtenOff", p);
    }

    /**
     * Names the invoice by every number an upstream mirror could be holding. `invoiceNumber` is the
     * one AR issued; `sourceInvoiceNumber` is the one the invoice arrived with, present only when the
     * two differ. A consumer that recorded a bill before AR took over numbering has the older one on
     * its row, and without it here that consumer cannot tell which of its debts an event is about —
     * it sees a payment against an invoice it has never heard of.
     *
     * <p>Absent on everything issued since AR began numbering, so ordinary traffic is unchanged and
     * the field reads as what it is: the other name this invoice answers to.
     */
    private static void putInvoiceNumbers(ObjectNode p, Invoice invoice) {
        p.put("invoiceNumber", invoice.getInvoiceNumber());
        String source = invoice.getSourceBillNumber();
        if (source != null && !source.equals(invoice.getInvoiceNumber())) {
            p.put("sourceInvoiceNumber", source);
        }
    }

    private ObjectNode disposition(Invoice invoice, String reason, String decidedBy) {
        ObjectNode p = JSON.createObjectNode();
        putInvoiceNumbers(p, invoice);
        p.put("debtorCode", invoice.getDebtor().getCode());
        p.put("amount", money(invoice.getAmount()));
        p.put("outstandingAtDecision", money(invoice.getOutstanding()));
        p.put("reason", reason);
        p.put("decidedBy", decidedBy);
        p.put("decidedAt", now());
        return p;
    }

    // ------------------------------------------------------------------ charge and payment events

    public String chargeOpened(Charge charge, Invoice invoice) {
        ObjectNode p = JSON.createObjectNode();
        putInvoiceNumbers(p, invoice);
        p.put("debtorCode", invoice.getDebtor().getCode());
        p.put("vaNumber", charge.getVaNumber());
        p.put("interbankVaNumber", properties.interbankVaPrefix() + charge.getVaNumber());
        p.put("bank", charge.getEscrowCode());
        p.put("amount", money(charge.getAmount()));
        p.put("expiresAt", charge.getExpiresAt().atZone(clock.getZone()).toLocalDate().minusDays(1).toString());
        return enqueue(properties.topics().invoiceEvent(), invoice.getDebtor().getCode(), "charge.opened", p);
    }

    public String chargeRepriced(Charge charge, Invoice invoice, String reason) {
        ObjectNode p = JSON.createObjectNode();
        putInvoiceNumbers(p, invoice);
        p.put("vaNumber", charge.getVaNumber());
        p.put("amount", money(charge.getAmount()));
        p.put("reason", reason);
        return enqueue(properties.topics().invoiceEvent(), invoice.getDebtor().getCode(), "charge.repriced", p);
    }

    public String chargeCancelled(Charge charge, Invoice invoice, String reason) {
        ObjectNode p = JSON.createObjectNode();
        putInvoiceNumbers(p, invoice);
        p.put("vaNumber", charge.getVaNumber());
        p.put("reason", reason);
        return enqueue(properties.topics().invoiceEvent(), invoice.getDebtor().getCode(), "charge.cancelled", p);
    }

    public String paymentReceived(Charge charge, CashApplication application, Invoice invoice) {
        return paymentReceived(charge.getVaNumber(), charge.getEscrowCode(), application, invoice);
    }

    /**
     * The same event for a payment that reached the books without a charge carrying it — booked from
     * a reconciliation finding, where the VA and the bank come from the bank's own record. The payload
     * is identical field for field: a campus app applies it exactly as it applies a live payment.
     */
    public String paymentReceived(String vaNumber, String bank, CashApplication application, Invoice invoice) {
        ObjectNode p = JSON.createObjectNode();
        putInvoiceNumbers(p, invoice);
        p.put("debtorCode", invoice.getDebtor().getCode());
        p.put("vaNumber", vaNumber);
        p.put("currency", invoice.getCurrency());
        p.put("amount", money(application.getAmount()));
        p.put("cumulativePaid", money(invoice.getAmount().subtract(invoice.getOutstanding())));
        p.put("outstanding", money(invoice.getOutstanding()));
        p.put("bank", bank);
        p.put("reference", application.getGatewayPaymentReference());
        p.put("paidAt", DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                OffsetDateTime.ofInstant(application.getReceivedAt(), clock.getZone())));
        p.put("invoiceStatus", invoice.getPaymentStatus().name());
        return enqueue(properties.topics().paymentEvent(), invoice.getDebtor().getCode(), "payment.received", p);
    }

    // ------------------------------------------------------------------ plumbing

    /** The most recent event of a type recorded for a debtor, for the idempotency store. */
    @Transactional(readOnly = true)
    public String lastEvent(String messageKey, String eventType) {
        List<ContractEventOutbox> rows = outboxRepository.findByMessageKeyOrderByCreatedAtAsc(messageKey);
        for (int i = rows.size() - 1; i >= 0; i--) {
            if (eventType.equals(rows.get(i).getEventType())) {
                return rows.get(i).getPayload();
            }
        }
        return null;
    }

    /** Republish a stored message as it was, eventId included. Used for idempotent repeats. */
    @Transactional
    public void republish(String topic, String messageKey, String eventType, String payload) {
        if (!properties.enabled()) {
            return;
        }
        save(topic, messageKey, eventType, payload);
    }

    @Transactional
    protected String enqueue(String topic, String messageKey, String type, ObjectNode payload) {
        if (!properties.enabled()) {
            return null;
        }
        ObjectNode envelope = JSON.createObjectNode();
        envelope.put("type", type);
        envelope.put("eventId", UUID.randomUUID().toString());
        envelope.put("occurredAt", now());
        envelope.put("producer", properties.producerName());
        envelope.set("payload", payload);
        String json;
        try {
            json = JSON.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize " + type, e);
        }
        save(topic, messageKey, type, json);
        return json;
    }

    private void save(String topic, String messageKey, String type, String json) {
        ContractEventOutbox row = new ContractEventOutbox();
        row.setTopic(topic);
        row.setMessageKey(messageKey);
        row.setEventType(type);
        row.setPayload(json);
        row.setStatus(ContractOutboxStatus.PENDING);
        row.setAttempts(0);
        row.setMaxAttempts(properties.maxAttempts());
        row.setNextAttemptAt(Instant.now(clock));
        outboxRepository.save(row);
    }

    static String money(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String now() {
        return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(OffsetDateTime.now(clock));
    }

    /**
     * Writes an optional field, or leaves it out entirely when there is nothing to say.
     *
     * <p>Leaves it out rather than writing {@code null}: every optional field in the published schema
     * is typed (a string with {@code minLength}, an invoice number), and {@code additionalProperties}
     * is false, so an explicit null makes the event fail the very schema the upstream teams validate
     * against. It used to emit nulls, which meant {@code invoice.issued} from an announcement and
     * {@code invoice.cancelled} for any reason but SUPERSEDED were both unvalidatable.
     */
    private static void putNullable(ObjectNode node, String field, String value) {
        if (value != null) {
            node.put(field, value);
        }
    }
}
