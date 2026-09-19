package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.CapturingNotificationPublisher;
import com.artivisi.accountreceivable.dto.NotificationRequest;
import com.artivisi.accountreceivable.entity.NotificationOutbox;
import com.artivisi.accountreceivable.entity.NotificationOutboxStatus;
import com.artivisi.accountreceivable.entity.NotificationSourceType;
import com.artivisi.accountreceivable.repository.AuditEventRepository;
import com.artivisi.accountreceivable.repository.NotificationOutboxRepository;
import com.artivisi.accountreceivable.service.NotificationDispatcher;
import com.artivisi.accountreceivable.service.notification.NotificationPayloadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class NotificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationOutboxRepository outboxRepository;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private CapturingNotificationPublisher publisher;
    @Autowired
    private AuditEventRepository auditEventRepository;
    @Autowired
    private NotificationPayloadMapper payloadMapper;

    @BeforeEach
    void resetNotificationState() {
        publisher.reset();
        outboxRepository.deleteAll();
    }

    // --------------------------------------------------------------------- helpers

    private void seed(String debtor, String type, String email) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", debtor);
        body.put("name", "Debtor " + debtor);
        if (email != null) {
            body.put("email", email);
        }
        body.put("status", "ACTIVE");
        given().contentType("application/json").body(body)
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    private String issueSingle(String debtor, String type, int amount) {
        return given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
    }

    private void openCharge(String invoiceId) {
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
    }

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(GATEWAY_CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void payWebhook(String consumerReference, String event, String chargeStatus,
                            int amount, String reference) {
        String body = "{\"eventType\":\"" + event + "\",\"consumerReference\":\"" + consumerReference
                + "\",\"chargeStatus\":\"" + chargeStatus + "\",\"paymentAmount\":" + amount
                + ",\"bankReference\":\"" + reference + "\"}";
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway").then().statusCode(200);
    }

    private List<NotificationOutbox> rowsFor(String sourceId) {
        return outboxRepository.findAll().stream()
                .filter(r -> sourceId.equals(r.getSourceId())).toList();
    }

    // --------------------------------------------------------------------- enqueue triggers

    @Test
    void openCharge_enqueuesBillReady_once() {
        seed("ntf-bill", "ntf-bill-type", "bill@example.test");
        String invoiceId = issueSingle("ntf-bill", "ntf-bill-type", 500000);

        openCharge(invoiceId);
        List<NotificationOutbox> rows = rowsFor(invoiceId);
        assertThat(rows).hasSize(1);
        NotificationOutbox row = rows.getFirst();
        assertThat(row.getConfigId()).isEqualTo("invoice-issued");
        assertThat(row.getSourceType()).isEqualTo(NotificationSourceType.INVOICE_ISSUED);
        assertThat(row.getRecipientEmail()).isEqualTo("bill@example.test");
        assertThat(row.getRecipientMobile()).isNull();   // sms-enabled=false
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.PENDING);
        assertThat(row.getData())
                .containsKeys("debtorName", "invoiceNumber", "invoiceType", "amount", "currency",
                        "dueDate", "vaNumber", "escrowCode")
                .containsEntry("amount", "500000.00");
        assertThat(row.getData().get("vaNumber")).isNotBlank();
        assertThat(NotificationPayloadMapper.BILL_ISSUED_VARIABLES).containsAll(row.getData().keySet());

        // Idempotent re-open: no second notification.
        openCharge(invoiceId);
        assertThat(rowsFor(invoiceId)).hasSize(1);
    }

    @Test
    void applyPayment_enqueuesPaymentReceived_notOnReplay() {
        seed("ntf-pay", "ntf-pay-type", "pay@example.test");
        String invoiceId = issueSingle("ntf-pay", "ntf-pay-type", 1000000);
        openCharge(invoiceId);

        payWebhook(invoiceId, "PAYMENT_RECEIVED", "PARTIALLY_PAID", 400000, "NTF-PAY-1");

        List<NotificationOutbox> paymentRows = outboxRepository.findAll().stream()
                .filter(r -> r.getSourceType() == NotificationSourceType.PAYMENT_RECEIVED)
                .filter(r -> "NTF-PAY-1".equals(r.getSourceId())).toList();
        assertThat(paymentRows).hasSize(1);
        NotificationOutbox row = paymentRows.getFirst();
        assertThat(row.getConfigId()).isEqualTo("payment-received");
        assertThat(row.getData())
                .containsEntry("paymentAmount", "400000")   // as received from the gateway webhook
                .containsEntry("outstanding", "600000.00")
                .containsEntry("paymentReference", "NTF-PAY-1")
                .containsKeys("cumulativePaid", "paidAt", "paidAtLocal");
        assertThat(NotificationPayloadMapper.PAYMENT_RECEIVED_VARIABLES).containsAll(row.getData().keySet());

        // Replay same bankReference: idempotent, no second notification.
        payWebhook(invoiceId, "PAYMENT_RECEIVED", "PARTIALLY_PAID", 400000, "NTF-PAY-1");
        assertThat(outboxRepository.findAll().stream()
                .filter(r -> "NTF-PAY-1".equals(r.getSourceId())).count()).isEqualTo(1);
    }

    @Test
    void overPayment_parked_enqueuesNothing() {
        seed("ntf-over", "ntf-over-type", "over@example.test");
        String invoiceId = issueSingle("ntf-over", "ntf-over-type", 100000);
        openCharge(invoiceId);
        // Clear the bill-ready row so we assert only on the payment path.
        outboxRepository.deleteAll();

        payWebhook(invoiceId, "PAYMENT_RECEIVED", "PAID", 150000, "NTF-OVER-1");
        assertThat(outboxRepository.findAll()).isEmpty();
    }

    @Test
    void dunningRun_enqueuesOverdue_viaEmailSender() {
        seed("ntf-dun", "ntf-dun-type", "dun@example.test");
        String overdueInvoice = given().contentType("application/json").body(Map.of(
                        "debtorCode", "ntf-dun", "invoiceTypeCode", "ntf-dun-type",
                        "issueDate", LocalDate.now().minusDays(50).toString(),
                        "dueDate", LocalDate.now().minusDays(20).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 250000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("invoiceNumber");

        given().contentType("application/json").body(Map.of("channel", "EMAIL", "minDaysOverdue", 0))
                .when().post("/api/dunning/runs").then().statusCode(201)
                .body("status", org.hamcrest.Matchers.equalTo("DONE"));

        List<NotificationOutbox> rows = rowsFor(overdueInvoice);
        assertThat(rows).hasSize(1);
        NotificationOutbox row = rows.getFirst();
        assertThat(row.getConfigId()).isEqualTo("invoice-overdue");
        assertThat(row.getSourceType()).isEqualTo(NotificationSourceType.DUNNING);
        assertThat(row.getRecipientEmail()).isEqualTo("dun@example.test");
        assertThat(row.getData())
                .containsEntry("invoiceNumber", overdueInvoice)
                .containsEntry("amountOutstanding", "250000.00")
                .containsKeys("debtorName", "currency", "dueDate", "daysOverdue");
        assertThat(NotificationPayloadMapper.DUNNING_VARIABLES).containsAll(row.getData().keySet());
    }

    @Test
    void noContactDebtor_skips_andAudits() {
        seed("ntf-nocontact", "ntf-nocontact-type", null);
        String invoiceId = issueSingle("ntf-nocontact", "ntf-nocontact-type", 100000);

        openCharge(invoiceId);   // must still succeed — notification is best-effort, never blocks

        assertThat(rowsFor(invoiceId)).isEmpty();
        assertThat(auditEventRepository.findAll().stream()
                .anyMatch(e -> "NOTIFICATION_SKIPPED".equals(e.getEventType())
                        && invoiceId.equals(e.getEntityId()))).isTrue();
    }

    // --------------------------------------------------------------------- dispatch

    @Test
    void dispatchDue_publishesRequest_andMarksSent() {
        seed("ntf-disp", "ntf-disp-type", "disp@example.test");
        String invoiceId = issueSingle("ntf-disp", "ntf-disp-type", 300000);
        openCharge(invoiceId);

        dispatcher.dispatchDue();

        NotificationOutbox row = rowsFor(invoiceId).getFirst();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.SENT);
        assertThat(row.getLastError()).isNull();

        assertThat(publisher.published()).hasSize(1);
        NotificationRequest sent = publisher.published().getFirst();
        assertThat(sent.configId()).isEqualTo("invoice-issued");
        assertThat(sent.email()).isEqualTo("disp@example.test");
        assertThat(sent.mobile()).isNull();
        assertThat(sent.data()).containsKey("vaNumber");
    }

    @Test
    void publishFailure_retriesToTerminalFailed_andAudits() {
        seed("ntf-fail", "ntf-fail-type", "fail@example.test");
        String invoiceId = issueSingle("ntf-fail", "ntf-fail-type", 100000);
        openCharge(invoiceId);

        publisher.setFailing(true);
        // max-attempts=3, backoff-base-seconds=0 → each retry is immediately due.
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();
        dispatcher.dispatchDue();

        NotificationOutbox row = rowsFor(invoiceId).getFirst();
        assertThat(row.getStatus()).isEqualTo(NotificationOutboxStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getLastError()).contains("stub publish failure");
        assertThat(auditEventRepository.findAll().stream()
                .anyMatch(e -> "NOTIFICATION_FAILED".equals(e.getEventType())
                        && invoiceId.equals(e.getEntityId()))).isTrue();
    }

    // --------------------------------------------------------------------- wire contract

    @Test
    void passthroughEnvelope_usesArsOwnFieldNames() {
        NotificationRequest request = new NotificationRequest(
                "invoice-issued", "a@example.test", null, Map.of("invoiceNumber", "INV-1"));
        assertThat(payloadMapper.envelope(request).keySet())
                .containsExactly("configId", "email", "mobile", "data");
    }
}
