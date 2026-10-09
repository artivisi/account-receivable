package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
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
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

class CollectionIntegrationTest extends AbstractIntegrationTest {

    @org.springframework.beans.factory.annotation.Autowired
    private com.artivisi.accountreceivable.repository.ChargeRepository chargeRepository;

    private void seed(String debtor, String type) {
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
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

    private static String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(GATEWAY_CLIENT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String webhook(String consumerReference, String event, String chargeStatus,
                                  int amount, String reference) {
        return "{\"eventType\":\"" + event + "\",\"consumerReference\":\"" + consumerReference
                + "\",\"chargeStatus\":\"" + chargeStatus + "\",\"paymentAmount\":" + amount
                + ",\"bankReference\":\"" + reference + "\"}";
    }

    @Test
    void openCharge_forwardsSourceBillNumberAsGatewayBillNumber() {
        seed("col-bill", "col-bill-type");
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", "col-bill", "invoiceTypeCode", "col-bill-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "sourceBillNumber", "2026010101000031",
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 400000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
        // The legacy bill number must reach the gateway as billNumber, which the adapter maps on
        // to whatever its bank calls the invoice number.
        org.assertj.core.api.Assertions.assertThat(lastChargeBillNumber).isEqualTo("2026010101000031");
    }

    @Test
    void openCharge_nativeInvoice_usesInvoiceNumberAsBillNumber() {
        seed("col-native", "col-native-type");
        String invoiceId = issueSingle("col-native", "col-native-type", 500000);
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
        // No source bill number → falls back to AR's own invoice number.
        org.assertj.core.api.Assertions.assertThat(lastChargeBillNumber).startsWith("INV");
    }

    @Test
    void openCharge_sendsDueDateAsGatewayExpiry() {
        seed("col-exp", "col-exp-type");
        LocalDate dueDate = LocalDate.now().plusDays(14);
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", "col-exp", "invoiceTypeCode", "col-exp-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", dueDate.toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 250000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);

        // Without this the gateway charge never lapses: the VA keeps answering inquiries forever and
        // the reaper never frees the number for the next bill.
        org.assertj.core.api.Assertions.assertThat(lastChargeExpiresAt)
                .as("expiresAt must reach the gateway as an ISO instant")
                .isNotNull();
        // Due date inclusive: payable through the due date, lapsing at the start of the next day —
        // the same window the replaced bank adapter gives (it stores the due date + 1 as its expiry).
        org.assertj.core.api.Assertions.assertThat(java.time.Instant.parse(lastChargeExpiresAt))
                .isEqualTo(dueDate.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
    }

    /**
     * A due date corrected upstream has to reach the payer, not stop at our books. Before this the
     * receivable kept the first version of a republished bill, which — once AR started sending
     * expiry — could leave a charge born already expired and a real payment refused at the bank.
     */
    @Test
    void amendDueDate_movesTheInvoiceAndTheGatewayCharge() {
        seed("col-amend", "col-amend-type");
        LocalDate original = LocalDate.now().plusDays(2);
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", "col-amend", "invoiceTypeCode", "col-amend-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", original.toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 300000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
        int extendsBefore = gatewayExtendCount();

        LocalDate corrected = original.plusMonths(2);
        given().contentType("application/json").body(Map.of("dueDate", corrected.toString()))
                .when().post("/api/invoices/{id}/due-date", invoiceId).then().statusCode(204);

        given().when().get("/api/invoices/{id}", invoiceId).then().statusCode(200)
                .body("dueDate", equalTo(corrected.toString()));
        org.assertj.core.api.Assertions.assertThat(gatewayExtendCount())
                .as("the gateway charge must be moved too, or the VA still lapses on the old date")
                .isEqualTo(extendsBefore + 1);
        org.assertj.core.api.Assertions.assertThat(java.time.Instant.parse(lastExtendExpiresAt))
                .isEqualTo(corrected.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
    }

    @Test
    void amendInstallmentDueDate_movingTheLastLegMovesThePlanChargeDeadline() {
        seed("col-inst-amend", "col-inst-amend-type");
        LocalDate lastDue = LocalDate.now().plusDays(40);
        var issued = given().contentType("application/json").body(Map.of(
                        "debtorCode", "col-inst-amend", "invoiceTypeCode", "col-inst-amend-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", lastDue.toString(),
                        "lines", List.of(Map.of("description", "Course", "quantity", 1, "unitAmount", 1000000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(5).toString(), "amount", 600000),
                                Map.of("dueDate", lastDue.toString(), "amount", 400000))))
                .when().post("/api/invoices").then().statusCode(201).extract();
        String invoiceId = issued.path("id");
        String lastInstallmentId = issued.path("installments[1].id");
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
        int extendsBefore = gatewayExtendCount();

        LocalDate corrected = lastDue.plusMonths(1);
        given().contentType("application/json").body(Map.of("dueDate", corrected.toString()))
                .when().post("/api/installments/{id}/due-date", lastInstallmentId).then().statusCode(204);

        // The plan's deadline is its last leg's, and the single charge follows it.
        given().when().get("/api/invoices/{id}", invoiceId).then().statusCode(200)
                .body("installments[1].dueDate", equalTo(corrected.toString()))
                .body("dueDate", equalTo(corrected.toString()));
        org.assertj.core.api.Assertions.assertThat(gatewayExtendCount())
                .as("the plan's gateway charge must be moved too, or its VA lapses with a leg unpaid")
                .isEqualTo(extendsBefore + 1);
        org.assertj.core.api.Assertions.assertThat(java.time.Instant.parse(lastExtendExpiresAt))
                .isEqualTo(corrected.plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
    }

    @Test
    void amendDueDate_refusesAnInstallmentInvoice_andPointsAtTheInstallment() {
        seed("col-inst-guard", "col-inst-guard-type");
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", "col-inst-guard", "invoiceTypeCode", "col-inst-guard-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Course", "quantity", 1, "unitAmount", 500000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 500000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        given().contentType("application/json")
                .body(Map.of("dueDate", LocalDate.now().plusDays(60).toString()))
                .when().post("/api/invoices/{id}/due-date", invoiceId)
                .then().statusCode(400);
    }

    @Test
    void amendInstallmentDueDate_refusesASettledInstallment() {
        seed("col-inst-paid", "col-inst-paid-type");
        var issued = given().contentType("application/json").body(Map.of(
                        "debtorCode", "col-inst-paid", "invoiceTypeCode", "col-inst-paid-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Course", "quantity", 1, "unitAmount", 700000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 700000))))
                .when().post("/api/invoices").then().statusCode(201).extract();
        String invoiceId = issued.path("id");
        String installmentId = issued.path("installments[0].id");
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
        settleGatewayCharge(invoiceId);
        String body = webhook(invoiceId, "CHARGE_PAID", "PAID", 700000, "BANK-INST-PAID-1");
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway").then().statusCode(200);

        // A settled installment has no deadline left to move, and moving one would silently
        // reactivate a VA against a debt that is already closed.
        given().contentType("application/json")
                .body(Map.of("dueDate", LocalDate.now().plusDays(90).toString()))
                .when().post("/api/installments/{id}/due-date", installmentId)
                .then().statusCode(400);
    }

    @Test
    void openCharge_isIdempotentPerInvoice() {
        seed("col-a", "col-a-type");
        String invoiceId = issueSingle("col-a", "col-a-type", 1000000);

        String chargeId = given().when().post("/api/invoices/{id}/charge", invoiceId)
                .then().statusCode(201)
                .body("gatewayChargeId", startsWith("gw-"))
                .body("consumerReference", equalTo(invoiceId))
                .body("vaNumber", notNullValue())
                .body("status", equalTo("ACTIVE"))
                .extract().path("id");

        given().when().post("/api/invoices/{id}/charge", invoiceId)
                .then().statusCode(201)
                .body("id", equalTo(chargeId));
    }

    @Test
    void webhook_appliesPartialPayment_andIsIdempotentOnReplay() {
        seed("col-b", "col-b-type");
        String invoiceId = issueSingle("col-b", "col-b-type", 1000000);
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);

        String body = webhook(invoiceId, "PAYMENT_RECEIVED", "PARTIALLY_PAID", 400000, "BANK-B-1");

        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway")
                .then().statusCode(200).body("status", equalTo("APPLIED"));

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200)
                .body("outstanding", equalTo(600000.0F))
                .body("paymentStatus", equalTo("PARTIALLY_PAID"));

        // Replay the same payment reference: no double application.
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway")
                .then().statusCode(200).body("status", equalTo("APPLIED"));

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200).body("outstanding", equalTo(600000.0F));
    }

    @Test
    void webhook_overPayment_isParkedUnapplied() {
        seed("col-c", "col-c-type");
        String invoiceId = issueSingle("col-c", "col-c-type", 100000);
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);

        String body = webhook(invoiceId, "PAYMENT_RECEIVED", "PAID", 150000, "BANK-C-1");
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway")
                .then().statusCode(200).body("status", equalTo("UNAPPLIED"));

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200)
                .body("outstanding", equalTo(100000.0F))
                .body("paymentStatus", equalTo("OPEN"));
    }

    /**
     * The gateway owns collectability, we own the receivable. A cancellation therefore updates our
     * charge mirror and stops: the invoice stays OPEN, because a retired VA is not a forgiven debt.
     * It becomes "billed but unpayable" for a human to resolve, not something inferred from an event.
     */
    @Test
    void webhook_chargeCancelled_mirrorsTheChargeAndLeavesTheReceivableOpen() {
        seed("col-cancel", "col-cancel-type");
        String invoiceId = issueSingle("col-cancel", "col-cancel-type", 750000);
        String gatewayChargeId = given().when().post("/api/invoices/{id}/charge", invoiceId)
                .then().statusCode(201).extract().path("gatewayChargeId");

        String body = "{\"eventType\":\"CHARGE_CANCELLED\",\"chargeId\":\"" + gatewayChargeId
                + "\",\"consumerReference\":\"" + invoiceId + "\",\"chargeStatus\":\"CANCELLED\"}";
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway").then().statusCode(200);

        assertThat(chargeRepository.findByGatewayChargeId(gatewayChargeId).orElseThrow().getStatus())
                .isEqualTo(com.artivisi.accountreceivable.entity.ChargeStatus.CANCELLED);

        // The debt is untouched — that is the whole point of the split.
        given().when().get("/api/invoices/{id}", invoiceId).then().statusCode(200)
                .body("paymentStatus", equalTo("OPEN"))
                .body("outstanding", equalTo(750000.0F));

        // Replay is a no-op, not a second mutation.
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway").then().statusCode(200)
                .body("note", equalTo("already cancelled"));
    }

    @Test
    void webhook_invalidSignature_isRejected() {
        seed("col-d", "col-d-type");
        String invoiceId = issueSingle("col-d", "col-d-type", 100000);
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);

        String body = webhook(invoiceId, "PAYMENT_RECEIVED", "PAID", 100000, "BANK-D-1");
        given().contentType("application/json").header("X-Signature", "deadbeef").body(body)
                .when().post("/webhooks/gateway")
                .then().statusCode(401);

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200).body("outstanding", equalTo(100000.0F));
    }

    @Test
    void planCharge_firstLegPaid_viaWebhook_reopensForTheNext() {
        seed("col-e", "col-e-type");
        var issued = given().contentType("application/json").body(Map.of(
                        "debtorCode", "col-e", "invoiceTypeCode", "col-e-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(60).toString(),
                        "lines", List.of(Map.of("description", "Course", "quantity", 1, "unitAmount", 1000000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 600000),
                                Map.of("dueDate", LocalDate.now().plusDays(60).toString(), "amount", 400000))))
                .when().post("/api/invoices").then().statusCode(201).extract();
        String invoiceId = issued.path("id");

        // One charge for the plan, worth the first leg, referenced by the invoice.
        given().when().post("/api/invoices/{id}/charge", invoiceId)
                .then().statusCode(201)
                .body("consumerReference", equalTo(invoiceId))
                .body("amount", equalTo(600000.0F));

        settleGatewayCharge(invoiceId);
        String body = webhook(invoiceId, "CHARGE_PAID", "PAID", 600000, "BANK-E-1");
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway")
                .then().statusCode(200).body("status", equalTo("APPLIED"));

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200)
                .body("outstanding", equalTo(400000.0F))
                .body("paymentStatus", equalTo("PARTIALLY_PAID"))
                .body("installments[0].paymentStatus", equalTo("PAID"))
                .body("installments[1].paymentStatus", equalTo("OPEN"));

        // The paid generation is spent; a new one is live on the same VA number for the next leg.
        var charges = chargeRepository.findByInvoiceId(invoiceId);
        org.assertj.core.api.Assertions.assertThat(charges).hasSize(2);
        var live = charges.stream().filter(c -> c.getStatus() == com.artivisi.accountreceivable.entity.ChargeStatus.ACTIVE)
                .findFirst().orElseThrow();
        var paid = charges.stream().filter(c -> c.getStatus() == com.artivisi.accountreceivable.entity.ChargeStatus.PAID)
                .findFirst().orElseThrow();
        org.assertj.core.api.Assertions.assertThat(live.getAmount()).isEqualByComparingTo("400000");
        org.assertj.core.api.Assertions.assertThat(live.getVaNumber()).isEqualTo(paid.getVaNumber());
        org.assertj.core.api.Assertions.assertThat(live.getConsumerReference()).isEqualTo(invoiceId + "#2");
    }
}
