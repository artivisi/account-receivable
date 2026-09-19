package com.artivisi.accountreceivable.ui;

import com.artivisi.accountreceivable.entity.ReceivableAnomaly;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import com.artivisi.accountreceivable.repository.ReceivableAnomalyRepository;
import com.artivisi.accountreceivable.service.ReceivableAnomalyService;
import com.microsoft.playwright.Dialog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/**
 * The worklist for findings raised outside AR. Like the upstream-missing screen it has to announce
 * itself: nothing on the invoice looks wrong from inside this ledger.
 */
class ReceivableAnomalyUiTest extends PlaywrightTestBase {

    @Autowired ReceivableAnomalyRepository anomalies;
    @Autowired InvoiceRepository invoices;

    private String debtor;
    private String type;

    @BeforeEach
    void setUp() {
        // The queue lists every open finding oldest first. Rows other test classes left open would
        // push this test's row off the first page, so start from an empty queue.
        jdbcTemplate.update("delete from receivable_anomaly");
        debtor = "ANOM-" + unique();
        type = "TAN" + unique();
        apiClient().debtor(debtor, "Debitur " + debtor, null, null, "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        loginAsAdmin();
    }

    private ReceivableAnomaly finding(String invoiceId, String ref, boolean withBankFigures) {
        ReceivableAnomaly a = new ReceivableAnomaly();
        a.setInvoice(invoices.findById(invoiceId).orElseThrow());
        a.setCategory(ReceivableAnomalyService.BANK_PAID_NOT_BOOKED);
        a.setSource("RECON");
        a.setDetail("Bank menerima Rp 750.000 untuk faktur ini, tidak pernah dibukukan");
        a.setEvidenceRef(ref);
        if (withBankFigures) {
            a.setEvidenceAmount(new BigDecimal("750000"));
            a.setEvidenceAt(Instant.parse("2026-07-16T08:16:32Z"));
            a.setEvidenceVaNumber("811234567890");
            a.setEvidenceBank("bsi");
        }
        a.setRaisedBy("recon-ui-test");
        return anomalies.saveAndFlush(a);
    }

    private String invoice() {
        return apiClient().issueSingle(debtor, type, 750_000, LocalDate.now(), LocalDate.now().plusDays(30))
                .path("id");
    }

    @Test
    void anUnbookedBankPaymentIsAnnouncedAndBookedFromTheQueue() {
        String invoiceId = invoice();
        String ref = "BANK-" + unique();
        String id = finding(invoiceId, ref, true).getId();

        page.navigate(baseUrl() + "/admin");
        assertThat(page.locator("#anomaly-banner")).isVisible();
        page.click("#btn-review-anomalies");

        page.waitForURL("**/admin/anomalies");
        assertThat(page.locator("#page-title")).containsText("Temuan Piutang");
        assertThat(page.locator("#evidence-ref-" + id)).containsText(ref);
        assertThat(page.locator("#evidence-amount-" + id)).containsText("750.000");
        assertThat(page.locator("#category-" + id)).containsText("Diterima bank, belum dibukukan");

        page.onDialog(Dialog::accept);
        page.fill("#input-book-note-" + id, "Dicocokkan dengan laporan transaksi bank");
        page.click("#btn-book-" + id);

        assertThat(page.locator("#flash-msg")).containsText("Pembayaran dicatat");
        assertThat(page.locator("#anomaly-row-" + id)).hasCount(0);
        given().port(port).get("/api/invoices/{id}", invoiceId).then().body("paymentStatus", equalTo("PAID"));

        page.navigate(baseUrl() + "/admin");
        assertThat(page.locator("#anomaly-banner")).hasCount(0);
    }

    @Test
    void aFindingWithoutBankFiguresIsShownOnTheInvoiceAndClosedWithADecision() {
        String invoiceId = invoice();
        String id = finding(invoiceId, "BANK-" + unique(), false).getId();

        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);
        assertThat(page.locator("#anomaly-warning")).isVisible();
        assertThat(page.locator("#anomaly-finding-" + id)).containsText("tidak pernah dibukukan");

        page.navigate(baseUrl() + "/admin/anomalies");
        assertThat(page.locator("#btn-book-" + id)).hasCount(0);
        assertThat(page.locator("#not-bookable-" + id)).containsText("amount");

        page.selectOption("#select-resolution-" + id, "ALREADY_RECORDED");
        page.fill("#input-resolve-note-" + id, "Sudah tercatat dengan referensi lain");
        page.click("#btn-resolve-" + id);

        assertThat(page.locator("#flash-msg")).containsText("Temuan ditutup");
        assertThat(page.locator("#anomaly-row-" + id)).hasCount(0);

        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);
        assertThat(page.locator("#anomaly-warning")).hasCount(0);
    }

    @Test
    void noBannerWhenNoFindingIsOpen() {
        page.navigate(baseUrl() + "/admin");
        assertThat(page.locator("#anomaly-banner")).hasCount(0);
    }
}
