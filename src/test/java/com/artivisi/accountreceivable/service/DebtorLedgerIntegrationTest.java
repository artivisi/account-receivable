package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.dto.DebtorDetailResponse;
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

/**
 * The debtor ledger reads collections through two separate queries — one for allocations against a
 * single-payment invoice, one for allocations against an installment — because expressing both in a
 * single query costs the planner every index it could use. That split is invisible from the outside,
 * so it needs a test that pays a debtor both ways and checks the ledger shows both credits.
 */
class DebtorLedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DebtorLedgerService debtorLedgerService;

    @Test
    void ledgerCarriesCreditsFromBothInvoiceAndInstallmentAllocations() {
        String debtor = "dl-both";
        String type = "dl-both-type";
        seed(debtor, type);

        // 1. a single-payment invoice, paid in full
        String invoiceId = issueSingle(debtor, type, 500_000);
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
        pay(invoiceId, "PAYMENT_RECEIVED", "PAID", 500_000, "DL-INV-1");

        // 2. an installment invoice; its first installment paid in full
        var issued = given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(60).toString(),
                        "lines", List.of(Map.of("description", "Course", "quantity", 1, "unitAmount", 1_000_000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 600_000),
                                Map.of("dueDate", LocalDate.now().plusDays(60).toString(), "amount", 400_000))))
                .when().post("/api/invoices").then().statusCode(201).extract();
        String planInvoiceId = issued.path("id");
        given().when().post("/api/invoices/{id}/charge", planInvoiceId).then().statusCode(201);
        settleGatewayCharge(planInvoiceId);
        pay(planInvoiceId, "CHARGE_PAID", "PAID", 600_000, "DL-INS-1");

        DebtorDetailResponse detail = debtorLedgerService.detail(debtor, 12);

        List<DebtorDetailResponse.LedgerEntry> credits = detail.ledger().stream()
                .filter(e -> e.credit() != null)
                .toList();

        assertThat(credits).extracting(DebtorDetailResponse.LedgerEntry::reference)
                .containsExactlyInAnyOrder("DL-INV-1", "DL-INS-1");

        // The installment credit names which installment it settled; the invoice one does not.
        assertThat(credits).filteredOn(e -> "DL-INS-1".equals(e.reference()))
                .singleElement()
                .satisfies(e -> assertThat(e.description()).isEqualTo("Pembayaran cicilan 1"));
        assertThat(credits).filteredOn(e -> "DL-INV-1".equals(e.reference()))
                .singleElement()
                .satisfies(e -> assertThat(e.description()).isEqualTo("Pembayaran"));

        // Both allocations count toward the trailing-12-month collections, so a branch silently
        // returning nothing would show up here even if the ledger rows were somehow satisfied.
        assertThat(detail.totalPaid12Months()).isEqualByComparingTo("1100000");
    }

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

    private void pay(String consumerReference, String event, String chargeStatus, int amount, String reference) {
        String body = "{\"eventType\":\"" + event + "\",\"consumerReference\":\"" + consumerReference
                + "\",\"chargeStatus\":\"" + chargeStatus + "\",\"paymentAmount\":" + amount
                + ",\"bankReference\":\"" + reference + "\"}";
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
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
