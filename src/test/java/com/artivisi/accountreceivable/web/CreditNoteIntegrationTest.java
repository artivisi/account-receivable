package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.service.ChargeCancellationDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

/**
 * A credit note is how a debt stops being collectible from the payer without money arriving: a
 * scholarship somebody else covers, a discount decided after issue, a mispriced bill. What makes it
 * more than bookkeeping is the gateway half — the VA must stop asking for what is no longer owed,
 * or the payer settles a bill that is already settled.
 */
class CreditNoteIntegrationTest extends AbstractIntegrationTest {

    @Autowired ChargeRepository chargeRepository;
    @Autowired ChargeCancellationDispatcher cancellationDispatcher;

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

    private void openCharge(String invoiceId) {
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
    }

    @Test
    void creditNote_reducesOutstanding() {
        seed("cn-acme", "cn-type");
        String invoiceId = issueSingle("cn-acme", "cn-type", 1000000);

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 200000,
                        "reasonCode", "DISCOUNT", "reason", "Potongan promo"))
                .when().post("/api/credit-notes")
                .then().statusCode(201)
                .body("creditNoteNumber", startsWith("CN"))
                .body("amount", equalTo(200000.0F))
                .body("reasonCode", equalTo("DISCOUNT"))
                .body("invoiceOutstanding", equalTo(800000.0F));

        // The invoice keeps its amount: the debt was that large, and the credit says who no longer owes it.
        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200)
                .body("amount", equalTo(1000000.0F))
                .body("outstanding", equalTo(800000.0F))
                .body("paymentStatus", equalTo("PARTIALLY_PAID"));
    }

    @Test
    void creditNote_exceedingOutstanding_isRejected() {
        seed("cn-over", "cn-over-type");
        String invoiceId = issueSingle("cn-over", "cn-over-type", 100000);

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 100001, "reasonCode", "CORRECTION"))
                .when().post("/api/credit-notes").then().statusCode(400);
    }

    @Test
    void creditNote_withoutAKind_isRejected() {
        seed("cn-kind", "cn-kind-type");
        String invoiceId = issueSingle("cn-kind", "cn-kind-type", 100000);

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 50000, "reason", "Beasiswa katanya"))
                .when().post("/api/credit-notes").then().statusCode(400);
    }

    @Test
    void scholarship_withoutTheDecisionItRestsOn_isRejected() {
        seed("cn-sch", "cn-sch-type");
        String invoiceId = issueSingle("cn-sch", "cn-sch-type", 100000);

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 50000, "reasonCode", "SCHOLARSHIP"))
                .when().post("/api/credit-notes").then().statusCode(400);

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 50000,
                        "reasonCode", "SCHOLARSHIP", "reference", "SK-2026-014"))
                .when().post("/api/credit-notes").then().statusCode(201)
                .body("reference", equalTo("SK-2026-014"));
    }

    @Test
    void fullCredit_stopsTheVaAskingForMoney() {
        seed("cn-full", "cn-full-type");
        String invoiceId = issueSingle("cn-full", "cn-full-type", 400000);
        openCharge(invoiceId);
        resetGatewayCancelStub();

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 400000,
                        "reasonCode", "SCHOLARSHIP", "reference", "SK-2026-015"))
                .when().post("/api/credit-notes").then().statusCode(201)
                .body("invoiceOutstanding", equalTo(0.0F));

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().body("paymentStatus", equalTo("PAID"));

        cancellationDispatcher.dispatchDue();
        assertThat(gatewayCancelCount()).isEqualTo(1);
        assertThat(chargeRepository.findByInvoiceId(invoiceId))
                .allMatch(c -> c.getStatus() == ChargeStatus.CANCELLED);
    }

    @Test
    void partialCredit_repricesTheVaToWhatIsLeft() {
        seed("cn-part", "cn-part-type");
        String invoiceId = issueSingle("cn-part", "cn-part-type", 1000000);
        openCharge(invoiceId);
        int repricesBefore = gatewayRepriceCount();

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 250000, "reasonCode", "DISCOUNT"))
                .when().post("/api/credit-notes").then().statusCode(201);

        assertThat(gatewayRepriceCount()).isEqualTo(repricesBefore + 1);
        assertThat(lastRepriceAmount).isEqualTo("750000.00");
    }

    @Test
    void creditNote_onAPlan_landsOnTheLastUnpaidLeg() {
        seed("cn-inst", "cn-inst-type");
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", "cn-inst", "invoiceTypeCode", "cn-inst-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(60).toString(),
                        "lines", List.of(Map.of("description", "Course", "quantity", 1, "unitAmount", 1000000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 500000),
                                Map.of("dueDate", LocalDate.now().plusDays(60).toString(), "amount", 500000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 200000, "reasonCode", "DISCOUNT"))
                .when().post("/api/credit-notes").then().statusCode(201);

        // The leg the payer was told about first keeps its figure; the credit lands on the last one.
        given().when().get("/api/invoices/{id}", invoiceId).then().statusCode(200)
                .body("outstanding", equalTo(800000.0F))
                .body("installments[0].outstanding", equalTo(500000.0F))
                .body("installments[1].outstanding", equalTo(300000.0F));
    }
}
