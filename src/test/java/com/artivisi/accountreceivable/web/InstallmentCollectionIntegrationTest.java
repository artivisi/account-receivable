package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.service.InstallmentRepricer;
import com.artivisi.accountreceivable.support.ApiClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * An instalment plan is collected on ONE CLOSED charge, on one VA number, whose amount is what the
 * plan is worth today. These tests pin the three things that make that work: the amount-due rule,
 * reopening on the same number after a leg is paid, and repricing when a leg falls due unpaid or
 * the plan is amended.
 */
class InstallmentCollectionIntegrationTest extends AbstractIntegrationTest {

    @Autowired ChargeRepository chargeRepository;
    @Autowired InstallmentRepricer repricer;
    private ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(port, GATEWAY_CLIENT_SECRET);
    }

    private String seedPlan(String debtor, LocalDate... dueDates) {
        api.debtor(debtor, "Debtor " + debtor, null, null, "ACTIVE");
        api.invoiceType(debtor + "-type", "Type", true);
        int per = 6_000_000 / dueDates.length;
        List<Map<String, Object>> legs = new java.util.ArrayList<>();
        for (LocalDate d : dueDates) legs.add(ApiClient.installment(d, per));
        Response issued = api.issue(debtor, debtor + "-type", LocalDate.now(), dueDates[dueDates.length - 1],
                "Plan", List.of(ApiClient.line("Course", 1, 6_000_000)), legs);
        return issued.path("id");
    }

    private void payLive(String invoiceId, String reference, int amount) {
        String liveRef = chargeRepository.findByInvoiceId(invoiceId).stream()
                .filter(c -> c.getStatus() == ChargeStatus.ACTIVE).findFirst().orElseThrow().getConsumerReference();
        settleGatewayCharge(liveRef);
        api.webhook(liveRef, "CHARGE_PAID", "PAID", amount, reference);
    }

    private com.artivisi.accountreceivable.entity.Charge live(String invoiceId) {
        return chargeRepository.findByInvoiceId(invoiceId).stream()
                .filter(c -> c.getStatus() == ChargeStatus.ACTIVE).findFirst().orElse(null);
    }

    @Test
    void planCharge_walksTheVaThroughEveryLeg_thenStops() {
        String invoiceId = seedPlan("plan-walk", LocalDate.now().plusDays(10), LocalDate.now().plusDays(40),
                LocalDate.now().plusDays(70));
        api.openCharge(invoiceId).then().body("amount", equalTo(2_000_000.0F));

        payLive(invoiceId, "WALK-1", 2_000_000);
        assertThat(live(invoiceId).getAmount()).isEqualByComparingTo("2000000");
        assertThat(live(invoiceId).getConsumerReference()).isEqualTo(invoiceId + "#2");

        payLive(invoiceId, "WALK-2", 2_000_000);
        assertThat(live(invoiceId).getConsumerReference()).isEqualTo(invoiceId + "#3");

        payLive(invoiceId, "WALK-3", 2_000_000);
        // Nothing left to collect: no live charge, and every generation settled on the same number.
        assertThat(live(invoiceId)).isNull();
        var all = chargeRepository.findByInvoiceId(invoiceId);
        assertThat(all).hasSize(3).allMatch(c -> c.getStatus() == ChargeStatus.PAID);
        assertThat(all.stream().map(c -> c.getVaNumber()).distinct()).hasSize(1);
        given().when().get("/api/invoices/{id}", invoiceId).then()
                .body("paymentStatus", equalTo("PAID")).body("outstanding", equalTo(0.0F));
    }

    @Test
    void overdueLegs_rollIntoWhatTheVaAnswers() {
        // Two legs already overdue, one to come: the VA asks for both overdue ones at once.
        String invoiceId = seedPlan("plan-roll", LocalDate.now().minusDays(20), LocalDate.now().minusDays(5),
                LocalDate.now().plusDays(40));
        api.openCharge(invoiceId).then().body("amount", equalTo(4_000_000.0F));

        // Time passes: the third leg falls due too. Moving its date is how a test makes that happen;
        // the periodic sync then reprices the live charge to the full remainder.
        int repricesBefore = gatewayRepriceCount();
        String thirdLegId = given().when().get("/api/invoices/{id}", invoiceId).then().extract().path("installments[2].id");
        given().contentType("application/json").body(Map.of("dueDate", LocalDate.now().minusDays(1).toString()))
                .when().post("/api/installments/{id}/due-date", thirdLegId).then().statusCode(204);
        assertThat(gatewayRepriceCount()).isEqualTo(repricesBefore + 1);
        assertThat(lastRepriceAmount).isEqualTo("6000000.00");
        assertThat(live(invoiceId).getAmount()).isEqualByComparingTo("6000000");

        // The sync is idempotent: nothing moved, nothing repriced.
        repricer.sweep();
        assertThat(gatewayRepriceCount()).isEqualTo(repricesBefore + 1);
    }

    @Test
    void amendPlan_replacesTheUnpaidLegs_andRepricesTheCharge() {
        String invoiceId = seedPlan("plan-amend", LocalDate.now().plusDays(10), LocalDate.now().plusDays(40),
                LocalDate.now().plusDays(70));
        api.openCharge(invoiceId);
        payLive(invoiceId, "AMEND-1", 2_000_000);

        int repricesBefore = gatewayRepriceCount();
        int extendsBefore = gatewayExtendCount();
        api.amendPlan(invoiceId, List.of(
                        ApiClient.installment(LocalDate.now().plusDays(40), 1_000_000),
                        ApiClient.installment(LocalDate.now().plusDays(70), 1_000_000),
                        ApiClient.installment(LocalDate.now().plusDays(100), 1_000_000),
                        ApiClient.installment(LocalDate.now().plusDays(130), 1_000_000)),
                "Permohonan disetujui, sisa dibagi empat")
                .then().statusCode(200)
                .body("installments.size()", equalTo(5))
                .body("installments[0].paymentStatus", equalTo("PAID"))
                .body("installments[4].sequence", equalTo(5))
                .body("dueDate", equalTo(LocalDate.now().plusDays(130).toString()))
                .body("outstanding", equalTo(4_000_000.0F));

        assertThat(live(invoiceId).getAmount()).isEqualByComparingTo("1000000");
        assertThat(gatewayRepriceCount()).isEqualTo(repricesBefore + 1);
        assertThat(gatewayExtendCount()).as("the plan's deadline moved with its last leg").isEqualTo(extendsBefore + 1);
    }

    @Test
    void amendPlan_isHowAPlanIsSettledEarly() {
        String invoiceId = seedPlan("plan-early", LocalDate.now().plusDays(10), LocalDate.now().plusDays(40),
                LocalDate.now().plusDays(70));
        api.openCharge(invoiceId);
        api.amendPlan(invoiceId, List.of(ApiClient.installment(LocalDate.now(), 6_000_000)), "Pelunasan dipercepat")
                .then().statusCode(200);
        assertThat(live(invoiceId).getAmount()).isEqualByComparingTo("6000000");
        payLive(invoiceId, "EARLY-1", 6_000_000);
        given().when().get("/api/invoices/{id}", invoiceId).then().body("paymentStatus", equalTo("PAID"));
    }

    @Test
    void amendPlan_refusesLegsThatDoNotSumToTheOutstanding() {
        String invoiceId = seedPlan("plan-sum", LocalDate.now().plusDays(10), LocalDate.now().plusDays(40));
        api.amendPlan(invoiceId, List.of(ApiClient.installment(LocalDate.now().plusDays(40), 1_000_000)), "salah")
                .then().statusCode(400);
    }

    @Test
    void issue_refusesAPlanWhoseLastLegDiffersFromTheInvoiceDueDate() {
        api.debtor("plan-due", "Debtor", null, null, "ACTIVE");
        api.invoiceType("plan-due-type", "Type", true);
        given().contentType("application/json").body(Map.of(
                        "debtorCode", "plan-due", "invoiceTypeCode", "plan-due-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(90).toString(),
                        "lines", List.of(ApiClient.line("Course", 1, 2_000_000)),
                        "installments", List.of(
                                ApiClient.installment(LocalDate.now().plusDays(10), 1_000_000),
                                ApiClient.installment(LocalDate.now().plusDays(40), 1_000_000))))
                .when().post("/api/invoices").then().statusCode(400);
    }
}
