package com.artivisi.accountreceivable.ui;

import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.restassured.RestAssured.given;

/**
 * The worklist for payments the originating billing system never booked.
 *
 * <p>This one has to announce itself. Every other exception screen is reachable because something in
 * this ledger looks wrong; here nothing does — the receivable is paid and the cash applied — so a
 * person would never think to go looking. Hence the banner on the page they already visit.
 */
class UpstreamMissingUiTest extends PlaywrightTestBase {

    private String debtor;
    private String type;

    @BeforeEach
    void setUp() {
        debtor = "DEB-" + unique();
        type = "TYP" + unique();
        apiClient().debtor(debtor, "Debitur " + debtor, debtor.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        loginAsAdmin();
    }

    @Test
    void aFlaggedPaymentIsAnnouncedAndCanBeResolvedOnTheSpot() {
        String reference = payAndFlag(1_250_000,
                "Tidak ada di aplikasi penagih: ditolak 'tidak memiliki va'");

        // Announced where finance already looks, because nothing here looks wrong on its own.
        page.navigate(baseUrl() + "/admin/cash-applications");
        assertThat(page.locator("#upstream-missing-banner")).isVisible();
        assertThat(page.locator("#upstream-missing-banner")).containsText("belum tercatat di aplikasi penagih");
        page.click("#btn-review-upstream-missing");

        page.waitForURL("**/upstream-missing");
        assertThat(page.locator("#page-title")).containsText("Belum Tercatat di Aplikasi Penagih");
        assertThat(page.locator("#upstream-missing-table")).containsText(reference);
        assertThat(page.locator("#upstream-missing-table")).containsText("1.250.000");
        assertThat(page.locator("#upstream-missing-table")).containsText("tidak memiliki va");

        String rowId = page.locator("[id^='upstream-row-']").first().getAttribute("id")
                .replace("upstream-row-", "");
        page.fill("#input-cleared-note-" + rowId, "Sudah diinput manual di aplikasi penagih");
        page.click("#btn-cleared-" + rowId);

        assertThat(page.locator("#flash-msg")).containsText("Ditandai sudah dicatat");
        assertThat(page.locator("#upstream-row-" + rowId)).hasCount(0);
    }

    @Test
    void noBannerWhenNothingIsMissing() {
        // A payment that was never flagged must not raise the alarm.
        payOnly(500_000);
        page.navigate(baseUrl() + "/admin/cash-applications");
        assertThat(page.locator("#upstream-missing-banner")).hasCount(0);
    }

    private String payOnly(int amount) {
        Response issued = apiClient()
                .issueSingle(debtor, type, amount, LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = issued.path("id");
        apiClient().openCharge(invoiceId);
        String reference = "BANK-" + unique();
        apiClient().webhook(invoiceId, "CHARGE_PAID", "PAID", amount, reference);
        return reference;
    }

    private String payAndFlag(int amount, String note) {
        String reference = payOnly(amount);
        given().port(port).post("/api/cash-applications/{ref}/upstream-missing?note={n}", reference, note)
                .then().statusCode(204);
        return reference;
    }
}
