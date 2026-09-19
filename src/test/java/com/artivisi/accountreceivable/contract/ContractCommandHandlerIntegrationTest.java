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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the command handler with the published examples in {@code contracts/v2/examples} — the
 * same files the upstream teams build against — and reads what lands in the event outbox. No
 * broker: the outbox is the contract's observable output.
 */
class ContractCommandHandlerIntegrationTest extends AbstractIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
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

    private List<ContractEventOutbox> eventsFor(String key, int after) {
        List<ContractEventOutbox> all = outbox.findByMessageKeyOrderByCreatedAtAsc(key);
        return all.subList(after, all.size());
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
        assertThat(invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getPaymentStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
        // The VA is retired at the gateway through the cancellation outbox; drain it here so the
        // charge is really dead, and so no pending row leaks into another test's dispatcher run.
        cancellationDispatcher.dispatchDue();
        String invoiceId = invoiceRepository.findByInvoiceNumber(invoiceNumber).orElseThrow().getId();
        assertThat(chargeRepository.findByInvoiceId(invoiceId))
                .allMatch(c -> c.getStatus() == ChargeStatus.CANCELLED);

        // Cancelled is final: a further amendment is refused as such, with the code the spec names.
        handler.handle("invoice-command", fixture("commands/invoice.amended/valid-amount-only.json", invoiceNumber));
        List<ContractEventOutbox> after = eventsFor(DEBTOR, before + 1);
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
    }
}
