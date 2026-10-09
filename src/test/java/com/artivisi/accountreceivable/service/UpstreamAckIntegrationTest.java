package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.exception.NotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;

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
 * Payments the originating billing system never booked.
 *
 * <p>The defining property, and the reason this needs its own mechanism: <b>nothing here is
 * wrong</b>. The receivable is paid, the cash is applied, the books balance. The divergence lives
 * entirely in the other system, so no query over this ledger can find it and the fact has to be
 * pushed in by whatever compares the two.
 *
 * <p>Not hypothetical: payments taken after a bank cutover were consumed by the upstream application
 * and discarded, and debtors who had paid in full were still shown as owing.
 */
class UpstreamAckIntegrationTest extends AbstractIntegrationTest {

    @Autowired private ReceivableReviewService reviewService;
    @Autowired private com.artivisi.accountreceivable.repository.CashApplicationRepository cashApplicationRepository;

    @Test
    void aPaymentMissingUpstreamIsFlaggedWithoutDisturbingOurBooks() {
        String reference = pay("ua-a", 900_000);

        reviewService.flagUpstreamMissing(reference,
                "Tidak ada di aplikasi penagih: ditolak dengan 'tidak memiliki va'");

        CashApplication row = reviewService.upstreamMissing(PageRequest.of(0, 50))
                .getContent().stream()
                .filter(c -> reference.equals(c.getPaymentReference()))
                .findFirst().orElseThrow();
        assertThat(row.getUpstreamMissingAt()).isNotNull();
        assertThat(row.getUpstreamMissingNote()).contains("tidak memiliki va");
        // The payment itself is untouched — flagging is a note about another system, not a reversal.
        assertThat(row.getAmount()).isEqualByComparingTo("900000");
        assertThat(row.getStatus().name()).isEqualTo("APPLIED");
    }

    /**
     * A check that runs daily re-reports the same unresolved divergence every time. It must not
     * stack duplicates, and must not reset the moment it was first seen — how long a payment has
     * been missing upstream is the thing that tells a reviewer how urgent it is.
     */
    @Test
    void reflaggingKeepsTheOriginalMoment() {
        String reference = pay("ua-b", 400_000);
        reviewService.flagUpstreamMissing(reference, "hari pertama");
        var first = reviewService.upstreamMissing(PageRequest.of(0, 50)).getContent().stream()
                .filter(c -> reference.equals(c.getPaymentReference())).findFirst().orElseThrow();
        var firstSeen = first.getUpstreamMissingAt();

        reviewService.flagUpstreamMissing(reference, "hari kedua, masih hilang");

        var again = reviewService.upstreamMissing(PageRequest.of(0, 50)).getContent().stream()
                .filter(c -> reference.equals(c.getPaymentReference())).toList();
        assertThat(again).hasSize(1);
        assertThat(again.get(0).getUpstreamMissingAt()).isEqualTo(firstSeen);
        assertThat(again.get(0).getUpstreamMissingNote()).isEqualTo("hari pertama");
    }

    @Test
    void clearingRemovesItFromTheWorklistButKeepsTheHistory() {
        String reference = pay("ua-c", 250_000);
        reviewService.flagUpstreamMissing(reference, "belum tercatat");
        String id = reviewService.upstreamMissing(PageRequest.of(0, 50)).getContent().stream()
                .filter(c -> reference.equals(c.getPaymentReference()))
                .findFirst().orElseThrow().getId();

        assertThatThrownBy(() -> reviewService.clearUpstreamMissing(id, " "))
                .isInstanceOf(InvalidRequestException.class);

        reviewService.clearUpstreamMissing(id, "Sudah diinput manual oleh Keuangan");

        assertThat(reviewService.upstreamMissing(PageRequest.of(0, 50)).getContent())
                .extracting(CashApplication::getPaymentReference)
                .doesNotContain(reference);
        // Kept, not deleted: a debtor whose payments keep going missing is its own problem, and
        // that pattern is only visible if cleared rows survive.
        CashApplication cleared = cashApplicationRepository.findById(id).orElseThrow();
        assertThat(cleared.getUpstreamMissingAt()).isNotNull();
        assertThat(cleared.getUpstreamClearedAt()).isNotNull();
        assertThat(cleared.getUpstreamClearedNote()).isEqualTo("Sudah diinput manual oleh Keuangan");
    }

    @Test
    void flaggingDemandsANoteAndAKnownReference() {
        String reference = pay("ua-d", 100_000);
        assertThatThrownBy(() -> reviewService.flagUpstreamMissing(reference, ""))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("required");
        assertThatThrownBy(() -> reviewService.flagUpstreamMissing("NO-SUCH-REF", "hilang"))
                .isInstanceOf(NotFoundException.class);
    }

    /** Issue, charge, pay — returns the gateway payment reference the two systems compare on. */
    private String pay(String prefix, int amount) {
        String debtor = prefix + "-deb";
        String type = prefix + "-type";
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
        String invoice = given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
        given().when().post("/api/invoices/{id}/charge", invoice).then().statusCode(201);

        String reference = "BANK-" + prefix.toUpperCase();
        String body = "{\"eventType\":\"CHARGE_PAID\",\"consumerReference\":\"" + invoice
                + "\",\"chargeStatus\":\"PAID\",\"paymentAmount\":" + amount
                + ",\"bankReference\":\"" + reference + "\"}";
        given().contentType("application/json").header("X-Signature", sign(body)).body(body)
                .when().post("/webhooks/gateway").then().statusCode(200);
        return reference;
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
