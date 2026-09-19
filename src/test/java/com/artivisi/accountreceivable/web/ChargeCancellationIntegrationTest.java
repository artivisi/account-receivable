package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.Charge;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.service.ChargeCancellationDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class ChargeCancellationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    ChargeCancellationDispatcher dispatcher;

    @Autowired
    ChargeRepository chargeRepository;

    @BeforeEach
    void reset() {
        resetGatewayCancelStub();
    }

    private void seed(String debtor, String type) {
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    private String issueAndCharge(String debtor, String type) {
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 500000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
        return invoiceId;
    }

    private String openCharge(String invoiceId) {
        return given().when().post("/api/invoices/{id}/charge", invoiceId)
                .then().statusCode(201).extract().path("id");
    }

    @Test
    void writeOff_cancelsOpenCharge_exactlyOnce() {
        seed("cc-a", "cc-a-type");
        String invoiceId = issueAndCharge("cc-a", "cc-a-type");
        String chargeId = openCharge(invoiceId);

        given().queryParam("reason", "uncollectible").when().post("/api/invoices/{id}/write-off", invoiceId).then().statusCode(200);

        dispatcher.dispatchDue();
        assertThat(gatewayCancelCount()).isEqualTo(1);
        assertThat(chargeRepository.findById(chargeId).orElseThrow().getStatus())
                .isEqualTo(ChargeStatus.CANCELLED);

        // Already DONE -> not re-dispatched.
        dispatcher.dispatchDue();
        assertThat(gatewayCancelCount()).isEqualTo(1);
    }

    /**
     * A charge mirrored in from another system carries that system's key as its consumer reference,
     * not the target id. Write-off used to resolve the charge by that reference, so it matched
     * nothing and cancelled nothing — silently, with the invoice still reported as written off.
     * The gateway kept collecting against a receivable the books had already forgiven.
     */
    @Test
    void writeOff_cancelsCharge_whenConsumerReferenceIsNotTheTargetId() {
        seed("cc-c", "cc-c-type");
        String invoiceId = issueAndCharge("cc-c", "cc-c-type");
        String chargeId = openCharge(invoiceId);

        // Re-key the charge the way an external mirror would: a foreign reference, FK untouched.
        Charge charge = chargeRepository.findById(chargeId).orElseThrow();
        charge.setConsumerReference("EXTERNAL-BILL-9001");
        chargeRepository.saveAndFlush(charge);

        given().queryParam("reason", "uncollectible").when().post("/api/invoices/{id}/write-off", invoiceId).then().statusCode(200);

        dispatcher.dispatchDue();
        assertThat(gatewayCancelCount()).isEqualTo(1);
        assertThat(chargeRepository.findById(chargeId).orElseThrow().getStatus())
                .isEqualTo(ChargeStatus.CANCELLED);
    }

    @Test
    void failedCancellation_retriesThenSucceeds() {
        seed("cc-b", "cc-b-type");
        String invoiceId = issueAndCharge("cc-b", "cc-b-type");
        String chargeId = openCharge(invoiceId);

        setGatewayCancelFail(true);
        given().queryParam("reason", "uncollectible").when().post("/api/invoices/{id}/write-off", invoiceId).then().statusCode(200);

        dispatcher.dispatchDue();
        assertThat(gatewayCancelCount()).isEqualTo(0);
        assertThat(chargeRepository.findById(chargeId).orElseThrow().getStatus())
                .isEqualTo(ChargeStatus.ACTIVE);

        setGatewayCancelFail(false);
        dispatcher.dispatchDue();
        assertThat(gatewayCancelCount()).isEqualTo(1);
        assertThat(chargeRepository.findById(chargeId).orElseThrow().getStatus())
                .isEqualTo(ChargeStatus.CANCELLED);
    }
}
