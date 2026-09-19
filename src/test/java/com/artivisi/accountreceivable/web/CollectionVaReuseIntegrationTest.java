package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.Charge;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.service.CollectionService;
import com.artivisi.accountreceivable.service.SupersededChargeSweeper;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The legacy billing system reuses one VA number across successive single bills (its way of doing
 * installments — no installment model). A new bill must supersede the still-active charge on that
 * number: the gateway reports the number active (409), AR cancels the prior charge and creates a
 * fresh single CLOSED charge.
 *
 * <p>And when the superseding bill is paid, the number comes free and the receivable queued behind it
 * — still owed, its collection retired only to make room — must go back into collection. Until that
 * existed, those debts were reported OPEN by AR, AKTIF by the billing master, and NOT_FOUND by the
 * gateway.
 */
@Import(CollectionVaReuseIntegrationTest.SameVaPerDebtor.class)
class CollectionVaReuseIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private SupersededChargeSweeper sweeper;

    @Autowired
    private ChargeRepository chargeRepository;

    @Autowired
    private CollectionService collectionService;

    @TestConfiguration
    static class SameVaPerDebtor {
        /** Every bill for a debtor lands on the same VA number, forcing the supersede path. */
        @Bean
        @Primary
        VaNumberSupplier reuseVaSupplier() {
            return ctx -> "920-" + ctx.debtorCode();
        }
    }

    private void seed(String debtor, String type) {
        // With an email, so opening a charge enqueues the bill-ready notification. Without one the
        // notification path is skipped, and it is the path that carries the consumer reference into
        // notification_outbox.source_id — where a reopened charge's generation suffix overflowed a
        // column sized for UUIDs, rolling back every repair on 2026-08-25.
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE",
                        "email", debtor + "@example.test"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    /** Issue a bill and open its charge; returns the invoice id (= the charge's first reference). */
    private String issueAndCharge(String debtor, String type, int amount) {
        return issueAndCharge(debtor, type, amount, LocalDate.now().plusDays(30));
    }

    private String issueAndCharge(String debtor, String type, int amount, LocalDate dueDate) {
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", dueDate.toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);
        return invoiceId;
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

    private void payInFull(String consumerReference, int amount, String bankReference) {
        String body = "{\"eventType\":\"CHARGE_PAID\",\"consumerReference\":\"" + consumerReference
                + "\",\"chargeStatus\":\"PAID\",\"paymentAmount\":" + amount
                + ",\"bankReference\":\"" + bankReference + "\"}";
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway").then().statusCode(200);
    }

    private List<Charge> chargesFor(String invoiceId) {
        return chargeRepository.findByInvoiceId(invoiceId);
    }

    private Charge liveChargeFor(String invoiceId) {
        return chargesFor(invoiceId).stream()
                .filter(c -> c.getStatus() == ChargeStatus.ACTIVE || c.getStatus() == ChargeStatus.PARTIALLY_PAID)
                .findFirst()
                .orElse(null);
    }

    @Test
    void aDeferredChargeIsRefusedWhileASiblingStillCollectsOnTheNumber() {
        seed("defer-deb", "defer-type");
        // Two bills of one type for one payer: the shape a campus app produces when it issues every
        // instalment up front. Both resolve to the same VA number.
        issueAndCharge("defer-deb", "defer-type", 1_000_000);
        String second = given().contentType("application/json").body(Map.of(
                        "debtorCode", "defer-deb", "invoiceTypeCode", "defer-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(60).toString(),
                        "lines", List.of(Map.of("description", "Leg 2", "quantity", 1, "unitAmount", 1_000_000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        // Opening it the ordinary way supersedes, which is what a new semester's bill is meant to do.
        // Opening it as a deferred leg must not: the sibling is the leg being paid right now.
        assertThatThrownBy(() -> collectionService.openDeferredChargeForInvoice(second))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("still collecting");
        assertThat(liveChargeFor(second)).as("nothing was opened for the sibling").isNull();
    }

    @Test
    void newBillSupersedesActiveChargeOnReusedVa() {
        resetGatewayCancelStub();
        seed("reuse-deb", "reuse-type");

        // First bill occupies the VA number.
        issueAndCharge("reuse-deb", "reuse-type", 1_000_000);
        // Second bill reuses it → gateway 409 → supersede cancels the first, then creates the second.
        issueAndCharge("reuse-deb", "reuse-type", 2_000_000);

        // Exactly the one prior charge was cancelled at the gateway; the new charge exists.
        assertThat(gatewayCancelCount()).isEqualTo(1);
    }

    @Test
    void supersessionIsRecordedAsItsOwnReason() {
        resetGatewayCancelStub();
        seed("reason-deb", "reason-type");

        String first = issueAndCharge("reason-deb", "reason-type", 1_000_000);
        issueAndCharge("reason-deb", "reason-type", 2_000_000);

        Charge superseded = chargesFor(first).getFirst();
        assertThat(superseded.getStatus()).isEqualTo(ChargeStatus.CANCELLED);
        // Both stamps: cancelled_at drives the review queue, superseded_at licenses an automatic
        // reopen. A write-off or a gateway-reported cancellation sets only the former.
        assertThat(superseded.getCancelledAt()).isNotNull();
        assertThat(superseded.getSupersededAt()).isNotNull();
    }

    @Test
    void sweepLeavesTheQueuedReceivableWhileTheSupersedingBillIsStillCollectible() {
        resetGatewayCancelStub();
        seed("hold-deb", "hold-type");

        String first = issueAndCharge("hold-deb", "hold-type", 6_000_000);
        issueAndCharge("hold-deb", "hold-type", 1_000_000);

        sweeper.sweep();

        // The number is occupied by a live bill, so nothing may be reopened onto it.
        assertThat(liveChargeFor(first)).isNull();
        assertThat(chargesFor(first)).hasSize(1);
    }

    @Test
    void payingTheSupersedingBillReopensTheReceivableQueuedBehindIt() {
        resetGatewayCancelStub();
        seed("queue-deb", "queue-type");

        String first = issueAndCharge("queue-deb", "queue-type", 6_000_000);
        String second = issueAndCharge("queue-deb", "queue-type", 1_000_000);
        String vaNumber = chargesFor(second).getFirst().getVaNumber();

        payInFull(second, 1_000_000, "REUSE-PAID-1");
        releaseGatewayVa(vaNumber);   // the gateway retires a fully paid charge's VA

        sweeper.sweep();

        Charge reopened = liveChargeFor(first);
        assertThat(reopened).isNotNull();
        // Same debt, same number the payer already knows, a new collection generation.
        assertThat(reopened.getVaNumber()).isEqualTo(vaNumber);
        assertThat(reopened.getAmount()).isEqualByComparingTo("6000000");
        assertThat(reopened.getConsumerReference()).isEqualTo(first + "#2");
        assertThat(reopened.getGatewayChargeId()).isNotEqualTo(chargesFor(second).getFirst().getGatewayChargeId());
        // Idempotent: a second sweep must not open a third generation.
        sweeper.sweep();
        assertThat(chargesFor(first)).hasSize(2);
    }

    @Test
    void reopeningByHandOpensAFreshGenerationRatherThanReturningTheDeadCharge() {
        resetGatewayCancelStub();
        seed("hand-deb", "hand-type");

        String first = issueAndCharge("hand-deb", "hand-type", 6_000_000);
        String second = issueAndCharge("hand-deb", "hand-type", 1_000_000);
        String vaNumber = chargesFor(second).getFirst().getVaNumber();

        payInFull(second, 1_000_000, "REUSE-PAID-2");
        releaseGatewayVa(vaNumber);

        // The repair path for receivables superseded before superseded_at existed: POST the charge
        // endpoint again. It used to answer 201 with the cancelled charge and open nothing.
        String reference = given().when().post("/api/invoices/{id}/charge", first)
                .then().statusCode(201).extract().path("consumerReference");

        assertThat(reference).isEqualTo(first + "#2");
        assertThat(liveChargeFor(first)).isNotNull();
    }

    @Test
    void reopenSkipsAReferenceTheGatewayHasAlreadySpent() {
        resetGatewayCancelStub();
        seed("spent-deb", "spent-type");

        String first = issueAndCharge("spent-deb", "spent-type", 6_000_000);
        String second = issueAndCharge("spent-deb", "spent-type", 1_000_000);
        String vaNumber = chargesFor(second).getFirst().getVaNumber();
        payInFull(second, 1_000_000, "REUSE-PAID-4");
        releaseGatewayVa(vaNumber);

        // Reproduce 2026-08-25: a repair opened the charge at the gateway, then failed before AR
        // saved its row, and the operator cancelled the orphan. The gateway keeps the reference and
        // now answers it with a cancelled charge; AR is back to having only the superseded row.
        given().when().post("/api/invoices/{id}/charge", first).then().statusCode(201);
        Charge orphaned = liveChargeFor(first);
        assertThat(orphaned.getConsumerReference()).isEqualTo(first + "#2");
        chargeRepository.delete(orphaned);
        cancelGatewayChargeByReference(first + "#2");
        assertThat(gatewayStatusOfReference(first + "#2")).isEqualTo("CANCELLED");

        // The retry must not adopt that cancelled charge — which is what reported success while
        // leaving eight students unable to pay.
        given().when().post("/api/invoices/{id}/charge", first).then().statusCode(201);

        Charge reopened = liveChargeFor(first);
        assertThat(reopened).isNotNull();
        assertThat(reopened.getConsumerReference()).isEqualTo(first + "#3");
        assertThat(reopened.getVaNumber()).isEqualTo(vaNumber);
        assertThat(gatewayStatusOfReference(first + "#3")).isEqualTo("ACTIVE");
    }

    @Test
    void reopenRefusesWhenTheGatewayHoldsMoneyUnderAReferenceArDoesNotKnow() {
        resetGatewayCancelStub();
        seed("paid-orphan-deb", "paid-orphan-type");

        String first = issueAndCharge("paid-orphan-deb", "paid-orphan-type", 6_000_000);
        String second = issueAndCharge("paid-orphan-deb", "paid-orphan-type", 1_000_000);
        String vaNumber = chargesFor(second).getFirst().getVaNumber();
        payInFull(second, 1_000_000, "REUSE-PAID-5");
        releaseGatewayVa(vaNumber);

        // Same orphan shape, but the orphan was PAID rather than cancelled: the gateway is holding a
        // real payment against a reference AR has no row for. Taking the next generation would
        // collect the same debt twice, so this stops for a person.
        given().when().post("/api/invoices/{id}/charge", first).then().statusCode(201);
        Charge orphaned = liveChargeFor(first);
        chargeRepository.delete(orphaned);
        payGatewayChargeByReference(first + "#2");

        given().when().post("/api/invoices/{id}/charge", first).then().statusCode(500);
        assertThat(liveChargeFor(first)).isNull();
    }

    @Test
    void sweepDoesNotReopenAReceivablePastItsDueDate() {
        resetGatewayCancelStub();
        seed("late-deb", "late-type");

        String first = issueAndCharge("late-deb", "late-type", 6_000_000, LocalDate.now().minusDays(2));
        String second = issueAndCharge("late-deb", "late-type", 1_000_000);
        String vaNumber = chargesFor(second).getFirst().getVaNumber();

        payInFull(second, 1_000_000, "REUSE-PAID-3");
        releaseGatewayVa(vaNumber);

        sweeper.sweep();

        // A charge born past its due date would be retired by the expiry sweep the same night. The
        // deadline is a decision for a person, so this one waits for POST /api/invoices/{id}/due-date.
        assertThat(liveChargeFor(first)).isNull();
    }
}
