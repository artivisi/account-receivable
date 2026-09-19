package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.dto.ReviewQueueItem;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The queue answers "which receivables recently became impossible to pay". Its value is entirely in
 * what it leaves out — a debt that is payable again, and eight years of migrated history — so those
 * exclusions are what this tests.
 */
class WriteOffQueueIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ReceivableReviewService reviewService;
    @Autowired private InvoiceService invoiceService;

    @Test
    void queueHoldsTheUnpayableAndExcludesWhatIsPayableAgain() {
        seed("woq", "woq-type");

        // 1. cancelled and still owed -> belongs in the queue
        String stranded = issue("woq", "woq-type", 500_000);
        String strandedCharge = openCharge(stranded);
        cancelAtGateway(strandedCharge, stranded);

        // 2. an installment invoice with one installment's charge cancelled and another still live.
        //    The receivable is still payable, so it must NOT appear — this is what the "no other
        //    collectible charge" clause is for, and it is the only shape that can produce it: a
        //    single-payment invoice cannot come back, because AR's openCharge is idempotent on
        //    consumerReference and returns the dead charge rather than issuing a new one.
        var issued = given().contentType("application/json").body(Map.of(
                        "debtorCode", "woq", "invoiceTypeCode", "woq-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(60).toString(),
                        "lines", List.of(Map.of("description", "Course", "quantity", 1, "unitAmount", 1_000_000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 600_000),
                                Map.of("dueDate", LocalDate.now().plusDays(60).toString(), "amount", 400_000))))
                .when().post("/api/invoices").then().statusCode(201).extract();
        String stillPayable = issued.path("id");
        // A plan collects on one live charge; while that charge is live the receivable is payable.
        given().when().post("/api/invoices/{id}/charge", stillPayable).then().statusCode(201);

        List<ReviewQueueItem> queue = reviewService.queue(7, 0, 100);

        assertThat(queue).extracting(ReviewQueueItem::invoiceId).contains(stranded);
        assertThat(queue).extracting(ReviewQueueItem::invoiceId).doesNotContain(stillPayable);

        ReviewQueueItem row = queue.stream()
                .filter(r -> stranded.equals(r.invoiceId())).findFirst().orElseThrow();
        assertThat(row.outstanding()).isEqualByComparingTo("500000");
        assertThat(row.cancelledAt()).isNotNull();
        assertThat(row.debtorCode()).isEqualTo("woq");
    }

    /**
     * Migrated history has no cancellation moment. The queue must key off the observed moment rather
     * than charge status, or thousands of historical invoices drown the handful that changed.
     */
    @Test
    void aCancellationOutsideTheWindowIsNotInTheQueue() {
        seed("woq-old", "woq-old-type");
        String invoice = issue("woq-old", "woq-old-type", 900_000);
        cancelAtGateway(openCharge(invoice), invoice);

        assertThat(reviewService.queue(7, 0, 100))
                .extracting(ReviewQueueItem::invoiceId).contains(invoice);

        // Zero-day window: the cancellation is already in the past, so nothing qualifies.
        assertThat(reviewService.queue(0, 0, 100))
                .extracting(ReviewQueueItem::invoiceId).doesNotContain(invoice);
    }

    @Test
    void writingOffDemandsAReasonAndKeepsIt() {
        seed("woq-reason", "woq-reason-type");
        String invoice = issue("woq-reason", "woq-reason-type", 250_000);

        assertThatThrownBy(() -> invoiceService.writeOff(invoice, "  "))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("reason is required");

        invoiceService.writeOff(invoice, "Debitur mengundurkan diri, disetujui Keuangan");

        given().when().get("/api/invoices/{id}", invoice).then().statusCode(200)
                .body("paymentStatus", org.hamcrest.Matchers.equalTo("WRITTEN_OFF"));
    }

    /** A written-off receivable is settled, so it drops out of the queue rather than lingering. */
    @Test
    void writingOffClearsTheRowFromTheQueue() {
        seed("woq-clear", "woq-clear-type");
        String invoice = issue("woq-clear", "woq-clear-type", 400_000);
        cancelAtGateway(openCharge(invoice), invoice);

        assertThat(idsInQueue()).contains(invoice);
        invoiceService.writeOff(invoice, "Tidak tertagih");
        assertThat(idsInQueue()).doesNotContain(invoice);
    }

    /**
     * A bill the source system retired belongs in the queue even though no VA ever died — that is
     * the whole point of recording the withdrawal separately. Before this, such a receivable was
     * reachable only through the mirror's failure log.
     */
    @Test
    void aWithdrawnBillEntersTheQueueEvenWithNoChargeAtAll() {
        seed("woq-wd", "woq-wd-type");
        String invoice = issue("woq-wd", "woq-wd-type", 750_000);

        // Never billed: no charge, so nothing has been cancelled and the queue ignores it.
        assertThat(idsInQueue()).doesNotContain(invoice);

        reviewService.withdraw(invoice, "Legacy REPLACE of bill 2026010102000002");

        ReviewQueueItem row = reviewService.queue(7, 0, 200).stream()
                .filter(r -> invoice.equals(r.invoiceId())).findFirst().orElseThrow();
        assertThat(row.withdrawnUpstream()).isTrue();
        assertThat(row.withdrawnReason()).contains("REPLACE");
        assertThat(row.verdict()).isEqualTo(ReviewQueueItem.Verdict.NEVER_BILLED);
        // The receivable itself is untouched — a withdrawal is evidence, never a decision.
        given().when().get("/api/invoices/{id}", invoice).then().statusCode(200)
                .body("paymentStatus", org.hamcrest.Matchers.equalTo("OPEN"))
                .body("outstanding", org.hamcrest.Matchers.equalTo(750000.0F));
    }

    @Test
    void withdrawingDemandsAReason() {
        seed("woq-wd2", "woq-wd2-type");
        String invoice = issue("woq-wd2", "woq-wd2-type", 100_000);
        assertThatThrownBy(() -> reviewService.withdraw(invoice, " "))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("reason is required");
    }

    /**
     * Keeping a receivable must silence it, or the queue re-presents every deliberate decision as an
     * unread one and gets abandoned within a week.
     */
    @Test
    void keepingCollectingClearsTheRowAndDemandsANote() {
        seed("woq-keep", "woq-keep-type");
        String invoice = issue("woq-keep", "woq-keep-type", 600_000);
        cancelAtGateway(openCharge(invoice), invoice);
        assertThat(idsInQueue()).contains(invoice);

        assertThatThrownBy(() -> reviewService.keepCollecting(invoice, ""))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("required");

        reviewService.keepCollecting(invoice, "Debitur sudah janji bayar pekan depan");
        assertThat(idsInQueue()).doesNotContain(invoice);
    }

    /**
     * A keep is a judgement about the evidence at the time. New evidence — the source system
     * retiring the bill — has to bring the row back, or the decision silently outlives its reason.
     */
    @Test
    void aFreshWithdrawalReopensAKeptRow() {
        seed("woq-reopen", "woq-reopen-type");
        String invoice = issue("woq-reopen", "woq-reopen-type", 300_000);
        cancelAtGateway(openCharge(invoice), invoice);
        reviewService.keepCollecting(invoice, "Masih ditagih");
        assertThat(idsInQueue()).doesNotContain(invoice);

        reviewService.withdraw(invoice, "Legacy HAPUS of bill 2026010102000009");
        assertThat(idsInQueue()).contains(invoice);
    }

    /**
     * A charge past its own deadline is not collectible, whatever its status says. Expiry is
     * enforced at read time and the sweep retires the VA, so the number answers nothing — but the
     * charge row stays ACTIVE, and a queue that reads status alone concludes the receivable is
     * still payable and hides it. That is how one genuinely dead receivable stayed out of this
     * queue while its invoice page claimed a student could still pay it.
     */
    @Test
    void anActiveChargePastItsDeadlineDoesNotCountAsPayable() {
        seed("woq-exp", "woq-exp-type");
        String invoice = given().contentType("application/json").body(Map.of(
                        "debtorCode", "woq-exp", "invoiceTypeCode", "woq-exp-type",
                        "issueDate", LocalDate.now().minusDays(60).toString(),
                        "dueDate", LocalDate.now().minusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 650_000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
        openCharge(invoice);
        // The charge is ACTIVE and its deadline is already past — nothing cancelled it.
        reviewService.withdraw(invoice, "Legacy REPLACE of bill 2026010102000099");

        ReviewQueueItem row = reviewService.queue(7, 0, 200).stream()
                .filter(r -> invoice.equals(r.invoiceId())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        "an ACTIVE-but-expired charge must not be read as still payable"));
        assertThat(row.outstanding()).isEqualByComparingTo("650000");
    }

    private List<String> idsInQueue() {
        return reviewService.queue(7, 0, 200).stream()
                .map(ReviewQueueItem::invoiceId).toList();
    }

    private void seed(String debtor, String type) {
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    private String issue(String debtor, String type, int amount) {
        return given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
    }

    private String openCharge(String invoiceId) {
        return given().when().post("/api/invoices/{id}/charge", invoiceId)
                .then().statusCode(201).extract().path("gatewayChargeId");
    }

    private void cancelAtGateway(String gatewayChargeId, String consumerReference) {
        String body = "{\"eventType\":\"CHARGE_CANCELLED\",\"chargeId\":\"" + gatewayChargeId
                + "\",\"consumerReference\":\"" + consumerReference + "\",\"chargeStatus\":\"CANCELLED\"}";
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
