package com.artivisi.accountreceivable.ui;

import com.artivisi.accountreceivable.support.ApiClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Moving a deadline from the admin screen — the case where a payer turns up after the bill lapsed.
 *
 * <p>Expiry is soft: the debt is never voided, so nothing is reinstated and there is no status to
 * unwind. The only correction is the date. Before these controls existed the capability was
 * API-only, so finance could not do this without an engineer.
 */
class DueDateUiTest extends PlaywrightTestBase {

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
    void overdueInvoice_dueDateMoved_thenChargeCanBeOpened() {
        // An invoice whose deadline has already passed — exactly what finance is handed when a
        // student appears late. It is still OPEN with its full balance, because expiry never
        // touches the receivable.
        var invoice = apiClient().issueSingle(debtor, type, 4_000_000,
                LocalDate.now().minusDays(80), LocalDate.now().minusDays(50));
        String invoiceId = invoice.path("id");

        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);
        assertThat(page.locator("#invoice-overdue")).containsText("Ya");

        LocalDate corrected = LocalDate.now().plusDays(14);
        page.fill("#input-due-date", corrected.toString());
        page.click("#btn-amend-due-date");

        assertThat(page.locator("#flash-msg")).containsText("Jatuh tempo diperbarui");
        assertThat(page.locator("#invoice-due-date")).containsText(corrected.toString());
        assertThat(page.locator("#invoice-overdue")).containsText("Tidak");

        // The point of moving the date: the charge that follows is born payable rather than
        // already lapsed, so the VA answers at the bank.
        page.click("#btn-open-charge");
        assertThat(page.locator("#flash-msg")).containsText("Charge berhasil dibuka");
    }

    /**
     * The screen an operator actually opens has to carry the warning. On 2026-08-19 one moved a due
     * date on a receivable whose debt had been collected under a replacement bill, because the page
     * said "OPEN, menunggak" and nothing else — the evidence existed, but only on a queue nobody
     * visits when they are chasing one named student.
     */
    @Test
    void anUnpayableReceivableWarnsOnItsOwnPage_notOnlyInTheQueue() {
        var invoice = apiClient().issueSingle(debtor, type, 900_000,
                LocalDate.now().minusDays(40), LocalDate.now().minusDays(10));
        String invoiceId = invoice.path("id");
        // Its VA dies, which is what leaves a receivable open but uncollectable.
        String gatewayChargeId = apiClient().openCharge(invoiceId).path("gatewayChargeId");
        apiClient().cancelCharge(gatewayChargeId, invoiceId);

        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);

        assertThat(page.locator("#unpayable-warning")).isVisible();
        assertThat(page.locator("#unpayable-warning"))
                .containsText("Piutang ini tidak bisa dibayar");
        // And it says so before the controls that look like the fix.
        assertThat(page.locator("#btn-amend-due-date")).isVisible();
    }

    /**
     * The inverse case, and the one that most needs a person: the source system says the bill is
     * withdrawn while the VA is still live, so a student can still pay it. It is deliberately kept
     * out of the write-off queue — that queue is for receivables nobody can pay, and putting a
     * collectible one there invites writing off a live debt — so the invoice page is where it has
     * to be said.
     */
    @Test
    void aWithdrawnButStillCollectibleReceivableSaysSoOnItsPage() {
        var invoice = apiClient().issueSingle(debtor, type, 3_125_000,
                LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = invoice.path("id");
        apiClient().openCharge(invoiceId);
        apiClient().withdraw(invoiceId, "Legacy REPLACE of bill 2026010140000020");

        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);

        assertThat(page.locator("#withdrawn-but-collectible")).isVisible();
        assertThat(page.locator("#withdrawn-but-collectible")).containsText("masih bisa dibayar");
        // Not the unpayable banner — this receivable is payable, and saying otherwise would be wrong.
        assertThat(page.locator("#unpayable-warning")).hasCount(0);
    }

    /** A healthy receivable must not carry the warning, or it becomes wallpaper. */
    @Test
    void aPayableReceivableShowsNoWarning() {
        var invoice = apiClient().issueSingle(debtor, type, 500_000,
                LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = invoice.path("id");
        apiClient().openCharge(invoiceId);

        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);
        assertThat(page.locator("#unpayable-warning")).hasCount(0);
    }

    @Test
    void installmentDueDate_movedPerInstallment() {
        // The invoice-level control is deliberately absent here — a schedule has one deadline per
        // installment, so the row owns the date.
        var invoice = apiClient().issue(debtor, type,
                LocalDate.now().minusDays(60), LocalDate.now().plusDays(30), "Cicilan",
                List.of(ApiClient.line("Paket", 1, 3_000_000)),
                List.of(ApiClient.installment(LocalDate.now().minusDays(30), 1_500_000),
                        ApiClient.installment(LocalDate.now().plusDays(30), 1_500_000)));
        String invoiceId = invoice.path("id");

        page.navigate(baseUrl() + "/admin/invoices/" + invoiceId);
        assertThat(page.locator("#input-due-date")).hasCount(0);
        assertThat(page.locator("#invoice-overdue")).containsText("Ya");

        LocalDate corrected = LocalDate.now().plusDays(10);
        page.fill("#input-installment-due-date-1", corrected.toString());
        page.click("#btn-installment-due-date-1");

        assertThat(page.locator("#flash-msg")).containsText("Jatuh tempo cicilan diperbarui");
        assertThat(page.locator("#installment-row-1")).containsText(corrected.toString());
        // The invoice's own overdue flag is derived from the earliest unpaid installment, so moving
        // the late one has to clear it — otherwise the recompute was skipped.
        assertThat(page.locator("#invoice-overdue")).containsText("Tidak");
    }
}
