package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import io.restassured.path.json.JsonPath;
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
