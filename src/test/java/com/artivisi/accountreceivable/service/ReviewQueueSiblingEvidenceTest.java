package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.dto.DueDateOutcome;
import com.artivisi.accountreceivable.dto.ReviewQueueItem;
import com.artivisi.accountreceivable.spi.VaNumberSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

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
 * The single inference this queue must refuse to make.
 *
 * <p>When a bill is replaced, the successor reuses the VA number and collects the debt — so a paid
 * charge on the same number usually does mean "already settled". Usually is not always: the number
 * is reused across periods too, so the paid charge may be an entirely different month's bill. On
 * 2026-08-18 that reasoning was applied to a live monthly UKT instalment whose paid "sibling" was
 * the previous month's, and it would have written off a receivable that was genuinely owed.
 *
 * <p>The queue therefore reports the sibling as evidence and labels the row
 * {@link ReviewQueueItem.Verdict#POSSIBLY_SETTLED} — never settled. This test exists to keep it that
 * way, because the tempting simplification is to promote the hint to a conclusion.
 *
 * <p>Uses a VA supplier keyed on the debtor so successive bills share a number, which is what the
 * originating system does; the default test supplier gives every invoice its own.
 */
@Import(ReviewQueueSiblingEvidenceTest.SameVaPerDebtor.class)
class ReviewQueueSiblingEvidenceTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class SameVaPerDebtor {
        @Bean
        @Primary
        VaNumberSupplier reuseVaSupplier() {
            return ctx -> "930-" + ctx.debtorCode();
        }
    }

    @Autowired private ReceivableReviewService reviewService;
    @Autowired private CollectionService collectionService;

    @Test
    void aPaidChargeOnTheSameVaNumberIsReportedAsPossibleNotProven() {
        seed("rqs", "rqs-type");
        String replaced = issue("rqs", "rqs-type", 900_000);
        openCharge(replaced);

        // The successor claims the same number, so AR supersedes the prior charge — the real
        // replacement flow, which is what stamps the cancellation moment the queue keys off.
        String successor = issue("rqs", "rqs-type", 900_000);
        openCharge(successor);
        String body = "{\"eventType\":\"CHARGE_PAID\",\"consumerReference\":\"" + successor
                + "\",\"chargeStatus\":\"PAID\",\"paymentAmount\":900000,"
                + "\"bankReference\":\"BANK-RQS-1\"}";
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway").then().statusCode(200);

        ReviewQueueItem row = reviewService.queue(7, 0, 200).stream()
                .filter(r -> replaced.equals(r.invoiceId())).findFirst().orElseThrow();

        assertThat(row.verdict())
                .as("a reused VA number is evidence to check, never proof this debt was settled")
                .isEqualTo(ReviewQueueItem.Verdict.POSSIBLY_SETTLED);
        assertThat(row.paidOnSameVaNumber()).isEqualByComparingTo("900000");
        assertThat(row.paidSiblingBill()).isNotNull();
        // Still owed and still in the queue: the evidence did not decide anything on its own.
        assertThat(row.outstanding()).isEqualByComparingTo("900000");
    }

    /**
     * The message an operator gets when they try to reactivate a superseded receivable. Moving the
     * date succeeds — the date really does move — but the thing they were trying to achieve does
     * not, and the old behaviour said only "saved". It must name the bill that holds the VA number,
     * because the next question is always "then which bill do I chase?".
     */
    @Test
    void movingTheDueDateOfASupersededReceivableReportsThatItIsStillUnpayable() {
        seed("rqs-due", "rqs-due-type");
        String replaced = issue("rqs-due", "rqs-due-type", 500_000);
        openCharge(replaced);
        String successor = issue("rqs-due", "rqs-due-type", 500_000);
        openCharge(successor);
        String body = "{\"eventType\":\"CHARGE_PAID\",\"consumerReference\":\"" + successor
                + "\",\"chargeStatus\":\"PAID\",\"paymentAmount\":500000,"
                + "\"bankReference\":\"BANK-RQS-DUE-1\"}";
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway").then().statusCode(200);

        DueDateOutcome outcome = collectionService.amendDueDate(replaced, LocalDate.now().plusDays(45), null);

        assertThat(outcome.kind()).isEqualTo(DueDateOutcome.Kind.STILL_UNPAYABLE);
        assertThat(outcome.restoredVas()).isZero();
        assertThat(outcome.blockingStatus()).isEqualTo("PAID");
        assertThat(outcome.blockingBillNumber())
                .as("name the bill that took the VA number, or the operator has nowhere to go next")
                .isNotNull();
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
