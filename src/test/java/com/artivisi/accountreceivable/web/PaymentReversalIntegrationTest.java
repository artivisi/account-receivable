package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.repository.ContractEventOutboxRepository;
import io.restassured.path.json.JsonPath;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

class PaymentReversalIntegrationTest extends AbstractIntegrationTest {

    @Autowired ContractEventOutboxRepository outbox;

    private void seed(String debtor, String type) {
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    private String issueAndCharge(String debtor, String type, int amount) {
        String id = given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
        given().when().post("/api/invoices/{id}/charge", id).then().statusCode(201);
        return id;
    }

    private void postWebhook(String json) {
        given().contentType("application/json").header("X-Signature", sign(json)).body(json)
                .when().post("/webhooks/gateway").then().statusCode(200);
    }

    @Test
    void reversal_restoresOutstanding_andIsIdempotent() {
        seed("rev-acme", "rev-type");
        String invoiceId = issueAndCharge("rev-acme", "rev-type", 1000000);

        postWebhook("{\"eventType\":\"PAYMENT_RECEIVED\",\"consumerReference\":\"" + invoiceId
                + "\",\"chargeStatus\":\"PARTIALLY_PAID\",\"paymentAmount\":400000,\"bankReference\":\"BANK-REV-1\"}");
        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200).body("outstanding", equalTo(600000.0F));

        String reversal = "{\"eventType\":\"PAYMENT_REVERSED\",\"bankReference\":\"BANK-REV-1\"}";
        given().contentType("application/json").header("X-Signature", sign(reversal)).body(reversal)
                .when().post("/webhooks/gateway")
                .then().statusCode(200).body("status", equalTo("REVERSED"));

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200)
                .body("outstanding", equalTo(1000000.0F))
                .body("paymentStatus", equalTo("OPEN"));

        // Replay: idempotent no-op.
        given().contentType("application/json").header("X-Signature", sign(reversal)).body(reversal)
                .when().post("/webhooks/gateway")
                .then().statusCode(200).body("status", equalTo("REVERSED"));
        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200).body("outstanding", equalTo(1000000.0F));

    }

    @Test
    void reversal_ofUnknownReference_isAcknowledged() {
        String reversal = "{\"eventType\":\"PAYMENT_REVERSED\",\"bankReference\":\"BANK-NOPE\"}";
        given().contentType("application/json").header("X-Signature", sign(reversal)).body(reversal)
                .when().post("/webhooks/gateway").then().statusCode(200);
    }

    /**
     * The reversal has to reach the upstream application, and exactly once.
     *
     * <p>Nothing a consumer holds can tell it that a payment went back: it raised the applicant's
     * status when it saw {@code payment.received}, and no later data contradicts that. SPMB reported
     * this on 2026-09-11 — without the event the applicant reads as settled for good. The replay
     * must stay silent, because a second reversal event would be read as a second reversal.
     */
    @Test
    void reversal_emitsPaymentReversedOnce_andNotOnReplay() {
        seed("rev-evt", "rev-evt-type");
        String invoiceId = issueAndCharge("rev-evt", "rev-evt-type", 500000);

        postWebhook("{\"eventType\":\"PAYMENT_RECEIVED\",\"consumerReference\":\"" + invoiceId
                + "\",\"chargeStatus\":\"PAID\",\"paymentAmount\":500000,\"bankReference\":\"BANK-REV-EVT\"}");

        String reversal = "{\"eventType\":\"PAYMENT_REVERSED\",\"bankReference\":\"BANK-REV-EVT\"}";
        postWebhook(reversal);

        List<ContractEventOutbox> reversed = outbox.findByMessageKeyOrderByCreatedAtAsc("rev-evt").stream()
                .filter(e -> "payment.reversed".equals(e.getEventType()))
                .toList();
        assertThat(reversed).hasSize(1);

        JsonPath event = JsonPath.from(reversed.getFirst().getPayload());
        assertThat(event.getString("payload.reference")).isEqualTo("BANK-REV-EVT");
        assertThat(event.getString("payload.source")).isEqualTo("GATEWAY");
        assertThat(event.getString("payload.debtorCode")).isEqualTo("rev-evt");
        // The receivable's state after the reversal, so the consumer overwrites instead of subtracting.
        assertThat(event.getString("payload.outstanding")).isEqualTo("500000.00");
        assertThat(event.getString("payload.invoiceStatus")).isEqualTo("OPEN");
        assertThat(event.getString("payload.reversedAt")).isNotBlank();

        postWebhook(reversal);
        assertThat(outbox.findByMessageKeyOrderByCreatedAtAsc("rev-evt").stream()
                .filter(e -> "payment.reversed".equals(e.getEventType()))
                .count()).isEqualTo(1);
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
}
