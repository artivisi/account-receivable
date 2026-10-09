package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.entity.InvoiceType;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.repository.ContractCommandRepository;
import com.artivisi.accountreceivable.repository.ContractEventOutboxRepository;
import com.artivisi.accountreceivable.repository.ContractFailureRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import com.artivisi.accountreceivable.repository.InvoiceTypeRepository;
import com.artivisi.accountreceivable.support.ApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the command handler with the published examples in {@code contracts/v2/examples} — the
 * same files the upstream teams build against — and reads what lands in the event outbox. No
 * broker: the outbox is the contract's observable output.
 */
class ContractCommandHandlerIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SCHEMA_ID = "https://artivisi.com/contracts/account-receivable/v2/messages.schema.json";
    private static final JsonSchemaFactory SCHEMAS = JsonSchemaFactory.getInstance(
            SpecVersion.VersionFlag.V202012,
            builder -> builder.schemaMappers(mappers -> mappers.mapPrefix(
                    "https://artivisi.com/contracts/account-receivable/v2/",
                    Path.of("contracts", "v2").toAbsolutePath().toUri().toString())));
    private static final SchemaValidatorsConfig SCHEMA_CONFIG =
            SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build();
    private static final Path EXAMPLES = Path.of("contracts", "v2", "examples");
    private static final String DEBTOR = "2600000001";

    @Autowired ContractCommandHandler handler;
    @Autowired ContractEventOutboxRepository outbox;
    @Autowired ContractCommandRepository commands;
    @Autowired ContractFailureRepository failures;
    @Autowired ChargeRepository chargeRepository;
    @Autowired com.artivisi.accountreceivable.service.ChargeCancellationDispatcher cancellationDispatcher;
    @Autowired InvoiceRepository invoiceRepository;
    @Autowired InvoiceTypeRepository invoiceTypeRepository;
    @Autowired com.artivisi.accountreceivable.repository.AuditEventRepository auditEvents;
    private ApiClient api;

    @BeforeEach
    void seed() {
        api = new ApiClient(port, GATEWAY_CLIENT_SECRET);
        if (invoiceTypeRepository.findByCode("40").isEmpty()) {
            InvoiceType t = new InvoiceType();
            t.setCode("40");
            t.setName("Uang Kuliah Tunggal (UKT)");
            t.setActive(true);
            invoiceTypeRepository.save(t);
        }
        handler.handle("debtor-command", fixture("commands/debtor.upserted/valid.json"));
    }

    // ------------------------------------------------------------------ helpers

    private static String fixture(String path) {
        try {
            return Files.readString(EXAMPLES.resolve(path));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A fixture with a fresh idempotency key and, where given, the invoice it should refer to. */
    private static String fixture(String path, String invoiceNumber) {
        try {
            ObjectNode root = (ObjectNode) JSON.readTree(fixture(path));
            ObjectNode payload = (ObjectNode) root.get("payload");
            if (payload.has("idempotencyKey")) {
                payload.put("idempotencyKey", payload.get("idempotencyKey").asText() + ":" + UUID.randomUUID());
            }
            if (invoiceNumber != null) {
                payload.put("invoiceNumber", invoiceNumber);
            }
            return JSON.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A single-payment request with a fresh key and arbitrary payload overrides. */
    private static String requestWith(java.util.Map<String, Object> overrides) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(fixture("commands/invoice.requested/valid-single-payment.json"));
        ObjectNode payload = (ObjectNode) root.get("payload");
        payload.put("idempotencyKey", payload.get("idempotencyKey").asText() + ":" + UUID.randomUUID());
        overrides.forEach((k, v) -> {
            if (v instanceof Boolean b) {
                payload.put(k, b);
            } else {
                payload.put(k, String.valueOf(v));
            }
        });
        return JSON.writeValueAsString(root);
    }

    private static String openRequest(String invoiceNumber) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(fixture("commands/charge.openRequested/valid.json"));
        ObjectNode payload = (ObjectNode) root.get("payload");
        payload.put("idempotencyKey", "spmb:buka-va:" + UUID.randomUUID());
        payload.put("invoiceNumber", invoiceNumber);
        return JSON.writeValueAsString(root);
    }

    private static String keyOf(String message) throws IOException {
        return JSON.readTree(message).get("payload").get("idempotencyKey").asText();
    }

    /**
     * The events a command produced — each one first validated against the published schema.
     *
     * <p>Validating here rather than in a test of its own is deliberate: every flow in this class
     * then checks the shape of what it emits for free, and a field that drifts from the contract
     * fails in our build instead of in an upstream team's listener. It caught AR writing an explicit
     * {@code null} for optional fields the schema types as strings.
     */
    private List<ContractEventOutbox> eventsFor(String key, int after) {
        List<ContractEventOutbox> all = outbox.findByMessageKeyOrderByCreatedAtAsc(key);
        List<ContractEventOutbox> produced = all.subList(after, all.size());
        for (ContractEventOutbox row : produced) {
            assertThat(validate(row)).as("%s does not match the published schema: %s",
                    row.getEventType(), row.getPayload()).isEmpty();
        }
        return produced;
    }

    private static Set<ValidationMessage> validate(ContractEventOutbox row) {
        try {
            return SCHEMAS.getSchema(SchemaLocation.of(SCHEMA_ID + "#/$defs/event." + row.getEventType()),
                    SCHEMA_CONFIG).validate(JSON.readTree(row.getPayload()));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode payloadOf(ContractEventOutbox row) throws IOException {
        return JSON.readTree(row.getPayload()).get("payload");
    }

    private String issuePlan() throws IOException {
        String message = fixture("commands/invoice.requested/valid-with-plan.json", null);
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        handler.handle("invoice-command", message);
        List<ContractEventOutbox> events = eventsFor(DEBTOR, before);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .containsExactly("invoice.issued", "invoice.planAmended", "charge.opened");
        return payloadOf(events.get(0)).get("invoiceNumber").asText();
    }

    /** A credit command with a fresh key, aimed at one invoice, for one amount. */
    private static String creditOf(String fixture, String invoiceNumber, String amount) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(fixture("commands/invoice.credited/" + fixture));
        ObjectNode payload = (ObjectNode) root.get("payload");
        payload.put("idempotencyKey", payload.get("idempotencyKey").asText() + ":" + UUID.randomUUID());
        payload.put("invoiceNumber", invoiceNumber);
        if (amount != null) {
            payload.put("amount", amount);
        }
        return JSON.writeValueAsString(root);
    }

    private String issueSingle() throws IOException {
        String message = fixture("commands/invoice.requested/valid-single-payment.json", null);
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        handler.handle("invoice-command", message);
        return payloadOf(eventsFor(DEBTOR, before).get(0)).get("invoiceNumber").asText();
    }

    // ------------------------------------------------------------------ tests

    @Test
    void requestedWithPlan_issuesAndOpensCharge_andARepeatRepublishesTheSameEvent() throws IOException {
        String message = fixture("commands/invoice.requested/valid-with-plan.json", null);
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", message);

        List<ContractEventOutbox> events = eventsFor(DEBTOR, before);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .containsExactly("invoice.issued", "invoice.planAmended", "charge.opened");
        JsonNode issued = payloadOf(events.get(0));
        assertThat(issued.get("correlationId").asText()).isEqualTo(keyOf(message));
        assertThat(issued.get("amount").asText()).isEqualTo("6500000.00");
        JsonNode plan = payloadOf(events.get(1));
        assertThat(plan.get("paymentPlan")).hasSize(3);
        assertThat(plan.get("correlationId").asText()).isEqualTo(keyOf(message));
        JsonNode opened = payloadOf(events.get(2));
        assertThat(opened.get("amount").asText()).as("the plan's first leg, not the total").isEqualTo("2166667.00");
        assertThat(opened.get("interbankVaNumber").asText()).startsWith("8888001").endsWith(opened.get("vaNumber").asText());
        assertThat(JSON.readTree(events.get(0).getPayload()).get("producer").asText()).isEqualTo("account-receivable-test");

        // The same command again: nothing new is issued, the first answer is republished verbatim.
        handler.handle("invoice-command", message);
        List<ContractEventOutbox> again = eventsFor(DEBTOR, before + 3);
        assertThat(again).hasSize(1);
        assertThat(again.get(0).getPayload()).isEqualTo(events.get(0).getPayload());
        assertThat(commands.findByIdempotencyKey(keyOf(message))).isPresent();
        assertThat(invoiceRepository.findByInvoiceNumber(issued.get("invoiceNumber").asText())).isPresent();
    }

    @Test
    void commandNamingTheBridgeEraNumber_findsTheSameInvoice() throws IOException {
        // An invoice issued while the bridge still allocated numbers: AR's own number on the row, the
        // number the campus app was told in sourceBillNumber. Upstream mirrors still quote the latter.
        String arNumber = issuePlan();
        com.artivisi.accountreceivable.entity.Invoice invoice =
                invoiceRepository.findByInvoiceNumber(arNumber).orElseThrow();
        String bridgeNumber = "2026082740999269";
        invoice.setSourceBillNumber(bridgeNumber);
        invoiceRepository.saveAndFlush(invoice);

        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        handler.handle("invoice-command",
                fixture("commands/invoice.announcementRequested/valid.json", bridgeNumber));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, before);
        assertThat(events).as("resolved by the bridge-era number, not rejected INVOICE_NOT_FOUND")
                .extracting(ContractEventOutbox::getEventType).contains("invoice.issued");
        JsonNode issued = payloadOf(events.get(0));
        assertThat(issued.get("invoiceNumber").asText())
                .as("the answer carries AR's own number").isEqualTo(arNumber);
        assertThat(issued.get("sourceInvoiceNumber").asText())
                .as("and the number the campus mirror is keyed on, so it can match the event")
                .isEqualTo(bridgeNumber);
    }

    @Test
    void anInvoiceKnownByOneNumberOnly_carriesNoSourceNumber() throws IOException {
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        issuePlan();

        // Nothing renamed this invoice, so the second name would be noise.
        for (ContractEventOutbox row : eventsFor(DEBTOR, before)) {
            assertThat(payloadOf(row).has("sourceInvoiceNumber"))
                    .as("%s should omit sourceInvoiceNumber", row.getEventType()).isFalse();
        }
    }

    @Test
    void requestedWithoutCharge_recordsTheDebtButOpensNoVa() throws IOException {
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", requestWith(java.util.Map.of("openCharge", false)));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, before);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .as("the receivable is on the books, nothing is payable yet")
                .containsExactly("invoice.issued");
    }

    @Test
    void openRequested_opensTheVaForAnInvoiceIssuedWithoutOne() throws IOException {
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        handler.handle("invoice-command", requestWith(java.util.Map.of("openCharge", false)));
        String invoiceNumber = payloadOf(eventsFor(DEBTOR, before).get(0)).get("invoiceNumber").asText();

        int afterIssue = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        handler.handle("invoice-command", openRequest(invoiceNumber));

        List<ContractEventOutbox> opened = eventsFor(DEBTOR, afterIssue);
        assertThat(opened).extracting(ContractEventOutbox::getEventType).containsExactly("charge.opened");
        assertThat(payloadOf(opened.get(0)).get("invoiceNumber").asText()).isEqualTo(invoiceNumber);
    }

    @Test
    void messageThatFailsTheSchema_isRejectedWithSchemaInvalid() throws IOException {
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        handler.handle("invoice-command", fixture("commands/invoice.requested/invalid-amount-as-number.json"));
        List<ContractEventOutbox> events = eventsFor(DEBTOR, before);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.get(0)).get("code").asText()).isEqualTo("SCHEMA_INVALID");
        assertThat(failures.count()).as("a sender's mistake is a rejection, not a failure").isZero();
    }

    @Test
    void unknownDebtor_isRejected_andTheSameCommandIsAnsweredAfreshNotFromTheStore() throws IOException {
        String message = fixture("commands/invoice.requested/rejected-unknown-debtor.json", null);
        String key = "2600009999";
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(key).size();

        handler.handle("invoice-command", message);
        List<ContractEventOutbox> events = eventsFor(key, before);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.get(0)).get("code").asText()).isEqualTo("DEBTOR_NOT_FOUND");

        // A rejection creates nothing, so idempotency has nothing to protect. Re-running it is how a
        // sender gets a different answer once the reason for the refusal is gone -- their key is
        // usually derived from the invoice, so a retry reproduces it and a stored answer would
        // outlive the defect that caused it.
        handler.handle("invoice-command", message);
        List<ContractEventOutbox> again = eventsFor(key, before + 1);
        assertThat(again).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(again.get(0)).get("code").asText()).isEqualTo("DEBTOR_NOT_FOUND");
        assertThat(again.get(0).getPayload())
                .as("answered again, not replayed from the idempotency store")
                .isNotEqualTo(events.get(0).getPayload());
        assertThat(commands.findByIdempotencyKey(keyOf(message)))
                .as("a refusal leaves no idempotency row to strand the next attempt").isEmpty();
    }

    @Test
    void payingALeg_emitsPaymentReceived_thenRolloverAndANewChargeOnTheSameNumber() throws IOException {
        String invoiceNumber = issuePlan();
        String invoiceId = invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getId();
        var live = chargeRepository.findByInvoiceId(invoiceId).stream()
                .filter(c -> c.getStatus() == ChargeStatus.ACTIVE).findFirst().orElseThrow();
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        settleGatewayCharge(live.getConsumerReference());
        api.webhook(live.getConsumerReference(), "CHARGE_PAID", "PAID", 2_166_667, "CTR-PAY-" + UUID.randomUUID());

        List<ContractEventOutbox> events = eventsFor(DEBTOR, before);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .containsExactly("payment.received", "charge.cancelled", "charge.opened");
        JsonNode paid = payloadOf(events.get(0));
        assertThat(paid.get("cumulativePaid").asText()).isEqualTo("2166667.00");
        assertThat(paid.get("outstanding").asText()).isEqualTo("4333333.00");
        assertThat(paid.get("invoiceStatus").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(events.get(0).getTopic()).isEqualTo("payment-event-test");
        assertThat(payloadOf(events.get(1)).get("reason").asText()).isEqualTo("INSTALLMENT_ROLLOVER");
        JsonNode reopened = payloadOf(events.get(2));
        assertThat(reopened.get("vaNumber").asText()).isEqualTo(live.getVaNumber());
        assertThat(reopened.get("amount").asText()).isEqualTo("2166667.00");
    }

    @Test
    void planAmended_emitsTheWholePlan_thenRepricesTheCharge() throws IOException {
        String invoiceNumber = issuePlan();
        String message = fixture("commands/invoice.planAmended/valid-split-remainder.json", invoiceNumber);
        // The fixture's four legs sum to the remainder after one paid leg; here nothing is paid yet,
        // so give it the whole amount over four legs instead.
        ObjectNode root = (ObjectNode) JSON.readTree(message);
        var plan = ((ObjectNode) root.get("payload")).putArray("paymentPlan");
        int[] amounts = {1_625_000, 1_625_000, 1_625_000, 1_625_000};
        for (int i = 0; i < 4; i++) {
            ObjectNode leg = plan.addObject();
            leg.put("sequence", i + 1);
            leg.put("dueDate", LocalDate.now().plusDays(30L * (i + 1)).toString());
            leg.put("amount", amounts[i] + ".00");
        }
        message = JSON.writeValueAsString(root);
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        int repricesBefore = gatewayRepriceCount();

        handler.handle("invoice-command", message);

        List<ContractEventOutbox> events = eventsFor(DEBTOR, before);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .containsExactly("invoice.planAmended", "charge.repriced");
        JsonNode amended = payloadOf(events.get(0));
        assertThat(amended.get("paymentPlan")).hasSize(4);
        assertThat(amended.get("correlationId").asText()).isEqualTo(keyOf(message));
        assertThat(amended.get("decidedBy").asText()).isEqualTo("aplikasi-akademik");
        // The upstream approval travels with the decision: echoed to the sender and kept in audit.
        assertThat(amended.get("reference").asText()).isEqualTo("KRG-2026-0142");
        assertThat(auditDetail("INVOICE_PLAN_AMENDED", invoiceNumber)).contains("reference=KRG-2026-0142");
        assertThat(payloadOf(events.get(1)).get("reason").asText()).isEqualTo("PLAN_AMENDED");
        assertThat(payloadOf(events.get(1)).get("amount").asText()).isEqualTo("1625000.00");
        assertThat(gatewayRepriceCount()).isEqualTo(repricesBefore + 1);
    }

    @Test
    void cancelled_marksTheInvoiceCancelled_andSaysSo() throws IOException {
        String invoiceNumber = issuePlan();
        String message = fixture("commands/invoice.cancelled/valid-duplicate.json", invoiceNumber);
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", message);

        List<ContractEventOutbox> events = eventsFor(DEBTOR, before);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.cancelled");
        JsonNode cancelled = payloadOf(events.get(0));
        assertThat(cancelled.get("reason").asText()).isEqualTo("DUPLICATE");
        assertThat(cancelled.get("outstandingAtDecision").asText()).isEqualTo("6500000.00");
        assertThat(cancelled.get("decidedBy").asText()).isEqualTo("aplikasi-akademik");
        // Optional until revision 5: a command without one is recorded without one, not filled in.
        assertThat(cancelled.has("reference")).isFalse();
        assertThat(invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        // The VA is retired at the gateway through the cancellation outbox; drain it here so the
        // charge is really dead, and so no pending row leaks into another test's dispatcher run.
        cancellationDispatcher.dispatchDue();
        String invoiceId = invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getId();
        assertThat(chargeRepository.findByInvoiceId(invoiceId))
                .allMatch(c -> c.getStatus() == ChargeStatus.CANCELLED);
        // Retiring the VA is announced: the upstream mirror cannot work out on its own that the
        // number it still shows the payer has stopped answering.
        assertThat(eventsFor(DEBTOR, before + 1)).extracting(ContractEventOutbox::getEventType)
                .containsExactly("charge.cancelled");

        // Cancelled is final: a further amendment is refused as such, with the code the spec names.
        handler.handle("invoice-command", fixture("commands/invoice.amended/valid-amount-only.json", invoiceNumber));
        List<ContractEventOutbox> after = eventsFor(DEBTOR, before + 2);
        assertThat(after).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(after.get(0)).get("code").asText()).isEqualTo("INVOICE_NOT_AMENDABLE");
    }

    @Test
    void amendedAmount_onASinglePaymentInvoice_repricesTheCharge() throws IOException {
        String message = fixture("commands/invoice.requested/valid-single-payment.json", null);
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        handler.handle("invoice-command", message);
        String invoiceNumber = payloadOf(eventsFor(DEBTOR, before).get(0)).get("invoiceNumber").asText();
        int repricesBefore = gatewayRepriceCount();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", fixture("commands/invoice.amended/valid-amount-only.json", invoiceNumber));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .containsExactly("charge.repriced", "invoice.issued");
        assertThat(payloadOf(events.get(0)).get("amount").asText()).isEqualTo("5000000.00");
        assertThat(payloadOf(events.get(0)).get("reason").asText()).isEqualTo("INVOICE_AMENDED");
        assertThat(payloadOf(events.get(1)).get("amount").asText()).isEqualTo("5000000.00");
        assertThat(gatewayRepriceCount()).isEqualTo(repricesBefore + 1);
        assertThat(auditDetail("INVOICE_AMOUNT_AMENDED", invoiceNumber)).contains("reference=KRG-2026-0143");
    }

    @Test
    void cancelledWithReference_echoesIt() throws IOException {
        String invoiceNumber = issuePlan();
        String replacement = issueSingle();
        ObjectNode root = (ObjectNode) JSON.readTree(
                fixture("commands/invoice.cancelled/valid-superseded.json", invoiceNumber));
        ((ObjectNode) root.get("payload")).put("replacedBy", replacement);
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", JSON.writeValueAsString(root));

        JsonNode cancelled = payloadOf(eventsFor(DEBTOR, before).get(0));
        assertThat(cancelled.get("reference").asText()).isEqualTo("KNV-2026-0007");
        assertThat(validate(eventsFor(DEBTOR, before).get(0))).isEmpty();
        assertThat(auditDetail("INVOICE_CANCELLED", invoiceNumber)).contains("reference=KNV-2026-0007");
        cancellationDispatcher.dispatchDue();
    }

    // ------------------------------------------------------------------ payment.recorded

    /** A recorded-payment command with a fresh key, aimed at one invoice, for one amount. */
    private static String recordedOf(String fixture, String invoiceNumber, String amount) throws IOException {
        ObjectNode root = (ObjectNode) JSON.readTree(fixture("commands/payment.recorded/" + fixture));
        ObjectNode payload = (ObjectNode) root.get("payload");
        String suffix = UUID.randomUUID().toString();
        payload.put("idempotencyKey", payload.get("idempotencyKey").asText() + ":" + suffix);
        payload.put("reference", payload.get("reference").asText() + "-" + suffix);
        if (invoiceNumber != null) {
            payload.put("invoiceNumber", invoiceNumber);
        }
        if (amount != null) {
            payload.put("amount", amount);
        }
        return JSON.writeValueAsString(root);
    }

    @Test
    void recordedInFull_booksTheCashAndStopsTheVaAskingForMoney() throws IOException {
        String invoiceNumber = issueSingle();
        resetGatewayCancelStub();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        String message = recordedOf("valid-cash-settles-invoice.json", invoiceNumber, "6500000.00");

        handler.handle("invoice-command", message);

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("payment.received");
        JsonNode p = payloadOf(events.getFirst());
        assertThat(p.get("source").asText()).isEqualTo("RECORDED");
        assertThat(p.get("channel").asText()).isEqualTo("CASH");
        // No VA was settled, so no VA number is reported — the schema forbids inventing one.
        assertThat(p.has("vaNumber")).isFalse();
        assertThat(p.has("bank")).isFalse();
        assertThat(p.get("amount").asText()).isEqualTo("6500000.00");
        assertThat(p.get("outstanding").asText()).isEqualTo("0.00");
        assertThat(p.get("invoiceStatus").asText()).isEqualTo("PAID");
        assertThat(p.get("reference").asText()).isEqualTo(
                JSON.readTree(message).get("payload").get("reference").asText());
        assertThat(p.get("paidAt").asText()).startsWith("2026-10-09T09:58");
        assertThat(invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.PAID);

        // The whole point: a bill settled at the counter must stop being payable, or the payer pays
        // it a second time by doing exactly what the bill told them to.
        cancellationDispatcher.dispatchDue();
        assertThat(gatewayCancelCount()).isEqualTo(1);
        String invoiceId = invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getId();
        assertThat(chargeRepository.findByInvoiceId(invoiceId))
                .allMatch(c -> c.getStatus() == ChargeStatus.CANCELLED);
        // And the upstream mirror must be told the VA is dead. It cannot derive this: AR decided it,
        // not the sender, and a mirror still showing a live VA keeps offering a number that answers
        // NOT_FOUND at the bank.
        assertThat(eventsFor(DEBTOR, mark + 1)).extracting(ContractEventOutbox::getEventType)
                .containsExactly("charge.cancelled");
        assertThat(payloadOf(eventsFor(DEBTOR, mark + 1).getFirst()).get("reason").asText())
                .isEqualTo("INVOICE_PAID");
    }

    @Test
    void recordedInPart_repricesTheVaToWhatIsLeft() throws IOException {
        String invoiceNumber = issueSingle();
        int repricesBefore = gatewayRepriceCount();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", recordedOf("valid-qris-part-payment.json", invoiceNumber, "1500000.00"));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .containsExactly("payment.received", "charge.repriced");
        assertThat(payloadOf(events.get(1)).get("amount").asText()).isEqualTo("5000000.00");
        assertThat(payloadOf(events.get(1)).get("reason").asText()).isEqualTo("PAYMENT_RECORDED");
        JsonNode paid = payloadOf(events.get(0));
        assertThat(paid.get("channel").asText()).isEqualTo("QRIS");
        assertThat(paid.get("cumulativePaid").asText()).isEqualTo("1500000.00");
        assertThat(paid.get("outstanding").asText()).isEqualTo("5000000.00");
        assertThat(paid.get("invoiceStatus").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(gatewayRepriceCount()).as("the VA must ask for the remainder, not the original")
                .isEqualTo(repricesBefore + 1);
    }

    @Test
    void recordedTwiceUnderTheSameKey_booksOnce() throws IOException {
        String invoiceNumber = issueSingle();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();
        String message = recordedOf("valid-cash-settles-invoice.json", invoiceNumber, "350000.00");

        handler.handle("invoice-command", message);
        handler.handle("invoice-command", message);

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .as("the repeat is answered from the idempotency store, not booked again")
                .containsExactly("payment.received", "charge.repriced", "payment.received");
        assertThat(events.get(2).getPayload()).isEqualTo(events.get(0).getPayload());
        assertThat(events.get(2).getTopic()).as("republished to the topic it first went to")
                .isEqualTo(events.get(0).getTopic());
        assertThat(invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getOutstanding())
                .isEqualByComparingTo("6150000.00");
    }

    @Test
    void recordedWithAReferenceAlreadyBooked_isRefused() throws IOException {
        String invoiceNumber = issueSingle();
        String first = recordedOf("valid-cash-settles-invoice.json", invoiceNumber, "350000.00");
        handler.handle("invoice-command", first);
        String reference = JSON.readTree(first).get("payload").get("reference").asText();
        // Same receipt number, different idempotency key: a second claim about the same money.
        ObjectNode root = (ObjectNode) JSON.readTree(recordedOf("valid-cash-settles-invoice.json", invoiceNumber, "350000.00"));
        ((ObjectNode) root.get("payload")).put("reference", reference);
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", JSON.writeValueAsString(root));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.getFirst()).get("code").asText()).isEqualTo("REFERENCE_DUPLICATE");
        assertThat(invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getOutstanding())
                .as("the second claim books nothing").isEqualByComparingTo("6150000.00");
    }

    @Test
    void recordedAboveOutstanding_isRefusedRatherThanParked() throws IOException {
        String invoiceNumber = issueSingle();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", recordedOf("rejected-exceeds-outstanding.json", invoiceNumber, "6500001.00"));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.getFirst()).get("code").asText()).isEqualTo("AMOUNT_INVALID");
        assertThat(invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getOutstanding())
                .isEqualByComparingTo("6500000.00");
    }

    @Test
    void recordedAgainstASettledInvoice_isRefused() throws IOException {
        String invoiceNumber = issueSingle();
        handler.handle("invoice-command", recordedOf("valid-cash-settles-invoice.json", invoiceNumber, "6500000.00"));
        cancellationDispatcher.dispatchDue();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", recordedOf("valid-cash-settles-invoice.json", invoiceNumber, "100000.00"));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.getFirst()).get("code").asText()).isEqualTo("INVOICE_NOT_PAYABLE");
    }

    /** The schema refuses it before AR looks at the invoice: `currency` is an enum of one. */
    @Test
    void recordedInAnotherCurrency_isRefused() throws IOException {
        String invoiceNumber = issueSingle();
        ObjectNode root = (ObjectNode) JSON.readTree(recordedOf("valid-cash-settles-invoice.json", invoiceNumber, "350000.00"));
        ((ObjectNode) root.get("payload")).put("currency", "USD");
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", JSON.writeValueAsString(root));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.getFirst()).get("code").asText()).isEqualTo("SCHEMA_INVALID");
    }

    @Test
    void recordedForAnUnknownInvoice_isRefused() throws IOException {
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", recordedOf("rejected-unknown-invoice.json", null, null));

        // Keyed by the correlation id, because no debtor could be resolved from an unknown invoice.
        List<ContractEventOutbox> all = outbox.findAll().stream()
                .filter(r -> "invoice.rejected".equals(r.getEventType())).toList();
        assertThat(all).isNotEmpty();
        ContractEventOutbox last = all.getLast();
        assertThat(payloadOf(last).get("code").asText()).isEqualTo("INVOICE_NOT_FOUND");
        assertThat(outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR)).hasSize(mark);
    }

    @Test
    void recordedWithAnUnknownChannel_isRefusedBySchema() throws IOException {
        String invoiceNumber = issueSingle();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", recordedOf("invalid-unknown-channel.json", invoiceNumber, null));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.getFirst()).get("code").asText()).isEqualTo("SCHEMA_INVALID");
        assertThat(invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getOutstanding())
                .isEqualByComparingTo("6500000.00");
    }

    private String auditDetail(String eventType, String invoiceNumber) {
        String invoiceId = invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getId();
        return auditEvents.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()) && invoiceId.equals(e.getEntityId()))
                .reduce((a, b) -> b).orElseThrow().getDetail();
    }

    @Test
    void creditedInFull_settlesTheInvoiceWithoutCash_andStopsTheVaAskingForMoney() throws IOException {
        String invoiceNumber = issueSingle();
        resetGatewayCancelStub();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", creditOf("valid-scholarship.json", invoiceNumber, "6500000.00"));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.credited");
        JsonNode p = payloadOf(events.getFirst());
        assertThat(p.get("amount").asText()).isEqualTo("6500000.00");
        // The invoice is worth what it always was; the credit note stands beside it.
        assertThat(p.get("invoiceAmount").asText()).isEqualTo("6500000.00");
        assertThat(p.get("outstanding").asText()).isEqualTo("0.00");
        assertThat(p.get("invoiceStatus").asText()).isEqualTo("PAID");
        assertThat(p.get("reasonCode").asText()).isEqualTo("SCHOLARSHIP");
        assertThat(p.get("reference").asText()).isEqualTo("SK-042/BEASISWA/2026");
        assertThat(p.get("creditNoteNumber").asText()).isNotBlank();

        // A bill settled by a scholarship must stop being payable, or the payer pays a debt nobody owes.
        cancellationDispatcher.dispatchDue();
        assertThat(gatewayCancelCount()).isEqualTo(1);
        String invoiceId = invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getId();
        assertThat(chargeRepository.findByInvoiceId(invoiceId))
                .allMatch(c -> c.getStatus() == ChargeStatus.CANCELLED);
    }

    @Test
    void creditedInPart_repricesTheVaToWhatIsLeft() throws IOException {
        String invoiceNumber = issueSingle();
        int repricesBefore = gatewayRepriceCount();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", creditOf("valid-correction-without-reference.json", invoiceNumber, "500000.00"));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType)
                .containsExactly("charge.repriced", "invoice.credited");
        assertThat(payloadOf(events.get(0)).get("amount").asText()).isEqualTo("6000000.00");
        JsonNode credited = payloadOf(events.get(1));
        assertThat(credited.get("outstanding").asText()).isEqualTo("6000000.00");
        // PARTIALLY_PAID although nobody paid: the status tracks what is still owed, not what was
        // collected. A consumer reading this as a payment would invent cash — which is why the event
        // carries outstanding and invoiceAmount, and why collection reports read cash applications.
        assertThat(credited.get("invoiceStatus").asText()).isEqualTo("PARTIALLY_PAID");
        assertThat(credited.has("reference")).isFalse();
        assertThat(gatewayRepriceCount()).isEqualTo(repricesBefore + 1);
    }

    @Test
    void creditExceedingWhatIsOwed_isRejected_andChangesNothing() throws IOException {
        String invoiceNumber = issueSingle();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command", creditOf("rejected-exceeds-outstanding.json", invoiceNumber, "9000000.00"));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.getFirst()).get("code").asText()).isEqualTo("AMOUNT_INVALID");
        assertThat(invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getOutstanding())
                .isEqualByComparingTo("6500000.00");
    }

    @Test
    void scholarshipWithoutTheDecisionItRestsOn_isRefusedAtTheDoor() throws IOException {
        String invoiceNumber = issueSingle();
        int mark = outbox.findByMessageKeyOrderByCreatedAtAsc(DEBTOR).size();

        handler.handle("invoice-command",
                creditOf("invalid-scholarship-without-reference.json", invoiceNumber, null));

        List<ContractEventOutbox> events = eventsFor(DEBTOR, mark);
        assertThat(events).extracting(ContractEventOutbox::getEventType).containsExactly("invoice.rejected");
        assertThat(payloadOf(events.getFirst()).get("code").asText()).isEqualTo("SCHEMA_INVALID");
    }
}
