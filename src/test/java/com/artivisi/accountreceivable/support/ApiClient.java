package com.artivisi.accountreceivable.support;

import io.restassured.response.Response;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;

/**
 * Thin RestAssured wrapper over the open {@code /api/**} endpoints and the gateway webhook, used by
 * both the demo-data seeder and the functional UI tests to set up preconditions without clicking
 * through the UI. All calls target the app on the given random test port.
 */
public class ApiClient {

    private final int port;
    private final String gatewaySecret;

    public ApiClient(int port, String gatewaySecret) {
        this.port = port;
        this.gatewaySecret = gatewaySecret;
    }

    public Response debtor(String code, String name, String email, String phone, String status) {
        var body = new HashMap<String, Object>();
        body.put("code", code);
        body.put("name", name);
        body.put("status", status);
        if (email != null) body.put("email", email);
        if (phone != null) body.put("phone", phone);
        return given().port(port).contentType("application/json").body(body)
                .post("/api/debtors").then().statusCode(201).extract().response();
    }

    public void invoiceType(String code, String name, boolean active) {
        given().port(port).contentType("application/json")
                .body(Map.of("code", code, "name", name, "active", active))
                .post("/api/invoice-types").then().statusCode(201);
    }

    public Response issue(String debtor, String type, LocalDate issueDate, LocalDate dueDate,
                          String description, List<Map<String, Object>> lines,
                          List<Map<String, Object>> installments) {
        var body = new HashMap<String, Object>();
        body.put("debtorCode", debtor);
        body.put("invoiceTypeCode", type);
        body.put("issueDate", issueDate.toString());
        body.put("dueDate", dueDate.toString());
        body.put("description", description);
        body.put("lines", lines);
        if (installments != null) body.put("installments", installments);
        return given().port(port).contentType("application/json").body(body)
                .post("/api/invoices").then().statusCode(201).extract().response();
    }

    /** Convenience: a single-line invoice for the given whole-rupiah amount. */
    public Response issueSingle(String debtor, String type, int amount, LocalDate issueDate, LocalDate dueDate) {
        return issue(debtor, type, issueDate, dueDate, "Tagihan",
                List.of(line("Item", 1, amount)), null);
    }

    public static Map<String, Object> line(String description, int quantity, int unitAmount) {
        return Map.of("description", description, "quantity", quantity, "unitAmount", unitAmount);
    }

    public static Map<String, Object> installment(LocalDate dueDate, int amount) {
        return Map.of("dueDate", dueDate.toString(), "amount", amount);
    }

    public Response openCharge(String invoiceId) {
        return given().port(port).post("/api/invoices/{id}/charge", invoiceId)
                .then().statusCode(201).extract().response();
    }

    /** Replace the unpaid part of a plan; legs must sum to the outstanding amount. */
    public Response amendPlan(String invoiceId, List<Map<String, Object>> installments, String reason) {
        return given().port(port).contentType("application/json")
                .body(Map.of("installments", installments, "reason", reason))
                .post("/api/invoices/{id}/plan", invoiceId).then().extract().response();
    }

    /** The originating system reports this bill retired — evidence for review, not a write-off. */
    public void withdraw(String invoiceId, String reason) {
        given().port(port).post("/api/invoices/{id}/withdraw?reason={r}", invoiceId, reason)
                .then().statusCode(204);
    }

    public void writeOff(String invoiceId) {
        writeOff(invoiceId, "test fixture");
    }

    public void writeOff(String invoiceId, String reason) {
        given().port(port).queryParam("reason", reason)
                .post("/api/invoices/{id}/write-off", invoiceId).then().statusCode(200);
    }

    public void creditNote(String invoiceId, int amount, String reason) {
        given().port(port).contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", amount, "reason", reason))
                .post("/api/credit-notes").then().statusCode(201);
    }

    public Response dunningRun(String channel, int minDaysOverdue) {
        return given().port(port).contentType("application/json")
                .body(Map.of("channel", channel, "minDaysOverdue", minDaysOverdue))
                .post("/api/dunning/runs").then().statusCode(201).extract().response();
    }

    /** Post a signed gateway webhook (matches the HMAC scheme the receiver validates). */
    public void webhook(String consumerReference, String event, String chargeStatus,
                        int amount, String bankReference) {
        String body = "{\"eventType\":\"" + event + "\",\"consumerReference\":\"" + consumerReference
                + "\",\"chargeStatus\":\"" + chargeStatus + "\",\"paymentAmount\":" + amount
                + ",\"bankReference\":\"" + bankReference + "\"}";
        given().port(port).contentType("application/json").header("X-Signature", sign(body)).body(body)
                .post("/webhooks/gateway").then().statusCode(200);
    }

    /**
     * The gateway announcing that it retired a charge's VA. Carries no payment — this is a
     * collectability event, not a money one.
     */
    public void cancelCharge(String gatewayChargeId, String consumerReference) {
        String body = "{\"eventType\":\"CHARGE_CANCELLED\",\"chargeId\":\"" + gatewayChargeId
                + "\",\"consumerReference\":\"" + consumerReference + "\",\"chargeStatus\":\"CANCELLED\"}";
        given().port(port).contentType("application/json").header("X-Signature", sign(body)).body(body)
                .post("/webhooks/gateway").then().statusCode(200);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(gatewaySecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
