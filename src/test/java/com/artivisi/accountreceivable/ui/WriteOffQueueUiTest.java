package com.artivisi.accountreceivable.ui;

import com.artivisi.accountreceivable.support.ApiClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * The write-off queue is the page finance opens to find receivables nobody can pay any more, and the
 * point where a debt gets forgiven. Both halves are exercised here against a real browser: a page
 * that renders only in production is a page nobody has tested.
 */
class WriteOffQueueUiTest extends PlaywrightTestBase {

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
    void queueRendersAndListsAReceivableWhoseVaDied() {
        Response issued = apiClient()
                .issueSingle(debtor, type, 750_000, LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = issued.path("id");
        String invoiceNumber = issued.path("invoiceNumber");
        cancelChargeAtGateway(invoiceId);

        page.navigate(baseUrl() + "/admin/invoices/write-off-queue");

        assertThat(page.locator("#page-title")).hasText("Antrean Hapus Buku");
        assertThat(page.locator("#write-off-queue-table")).containsText(invoiceNumber);
        assertThat(page.locator("#queue-row-" + invoiceId)).isVisible();
        // The debtor and what is owed are on the row: this is a page for deciding, not just listing.
        assertThat(page.locator("#queue-row-" + invoiceId)).containsText(debtor);
        assertThat(page.locator("#queue-row-" + invoiceId)).containsText("750.000");
    }

    @Test
    void anEmptyWindowSaysSoRatherThanRenderingAnEmptyTable() {
        page.navigate(baseUrl() + "/admin/invoices/write-off-queue?days=0");
        assertThat(page.locator("#write-off-queue-table"))
                .containsText("Tidak ada piutang yang menunggu keputusan");
    }

    /** Writing off from the review page clears the row, because the receivable is settled. */
    @Test
    void writingOffWithAReasonRemovesItFromTheQueue() {
        Response issued = apiClient()
                .issueSingle(debtor, type, 250_000, LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = issued.path("id");
        cancelChargeAtGateway(invoiceId);

        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);
        page.onDialog(dialog -> dialog.accept());
        page.fill("#input-write-off-reason", "Debitur mengundurkan diri");
        page.click("#btn-write-off");
        assertThat(page.locator("#invoice-status")).hasText("WRITE-OFF");

        page.navigate(baseUrl() + "/admin/invoices/write-off-queue");
        assertThat(page.locator("#queue-row-" + invoiceId)).hasCount(0);
    }

    /**
     * The decision has to be takeable from the queue itself. Bouncing a reviewer working a list of
     * twenty into a detail page and back for each one is how a review session gets abandoned.
     */
    @Test
    void aReceivableCanBeWrittenOffWithoutLeavingTheQueue() {
        Response issued = apiClient()
                .issueSingle(debtor, type, 320_000, LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = issued.path("id");
        cancelChargeAtGateway(invoiceId);

        page.navigate(baseUrl() + "/admin/invoices/write-off-queue");
        page.onDialog(dialog -> dialog.accept());
        page.fill("#input-write-off-reason-" + invoiceId, "Disetujui Keuangan");
        page.click("#btn-write-off-" + invoiceId);

        assertThat(page.locator("#page-title")).hasText("Antrean Hapus Buku");
        assertThat(page.locator("#queue-row-" + invoiceId)).hasCount(0);
    }

    /** Keeping a receivable silences it, so deliberate decisions stop reappearing as unread ones. */
    @Test
    void keepingAReceivableClearsItFromTheQueueButLeavesTheDebt() {
        Response issued = apiClient()
                .issueSingle(debtor, type, 410_000, LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = issued.path("id");
        cancelChargeAtGateway(invoiceId);

        page.navigate(baseUrl() + "/admin/invoices/write-off-queue");
        page.fill("#input-keep-note-" + invoiceId, "Sudah janji bayar pekan depan");
        page.click("#btn-keep-" + invoiceId);

        assertThat(page.locator("#flash-msg")).containsText("Ditandai masih tertagih");
        assertThat(page.locator("#queue-row-" + invoiceId)).hasCount(0);

        // Still owed — keeping is a decision about collection, not about the debt.
        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);
        assertThat(page.locator("#invoice-outstanding")).containsText("410.000");
    }

    /**
     * A bill the source system retired reaches the queue with no dead VA at all, and says so — this
     * is the path that previously ended in the mirror's failure log where nobody saw it.
     */
    @Test
    void aBillWithdrawnUpstreamAppearsWithItsReason() {
        Response issued = apiClient()
                .issueSingle(debtor, type, 555_000, LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = issued.path("id");
        apiClient().withdraw(invoiceId, "Legacy REPLACE of bill 2026010102000002");

        page.navigate(baseUrl() + "/admin/invoices/write-off-queue");

        assertThat(page.locator("#queue-row-" + invoiceId)).isVisible();
        assertThat(page.locator("#withdrawn-" + invoiceId)).hasText("Ditarik sistem penagih");
        assertThat(page.locator("#verdict-" + invoiceId)).hasText("Belum pernah ditagih");
        assertThat(page.locator("#queue-row-" + invoiceId)).containsText("REPLACE");
    }

    private void cancelChargeAtGateway(String invoiceId) {
        String gatewayChargeId = apiClient().openCharge(invoiceId).path("gatewayChargeId");
        apiClient().cancelCharge(gatewayChargeId, invoiceId);
    }
}
