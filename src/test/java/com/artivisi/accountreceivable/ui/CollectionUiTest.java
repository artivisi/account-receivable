package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Tagihan VA screen (charges only — cash applications have their own dedicated page, see
 * {@link CashApplicationUiTest}). Preconditions (issue → open charge → signed payment webhook) are
 * set up via the API/webhook; the tests assert the resulting charge row renders with its
 * human-readable identifiers (invoice number, debtor name) and that search narrows by them.
 */
class CollectionUiTest extends PlaywrightTestBase {

    @BeforeEach
    void authenticate() {
        loginAsAdmin();
    }

    @Test
    void charge_appearsAfterWebhook() {
        String debtor = "DEB-" + unique();
        String type = "TYP" + unique();
        apiClient().debtor(debtor, "Debitur " + debtor, debtor.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);

        var invoice = apiClient()
                .issueSingle(debtor, type, 1_000_000, LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceId = invoice.path("id");
        String invoiceNumber = invoice.path("invoiceNumber");
        apiClient().openCharge(invoiceId);
        String bankRef = "BANK-" + unique();
        apiClient().webhook(invoiceId, "PAYMENT_RECEIVED", "PARTIALLY_PAID", 400_000, bankRef);

        page.navigate(baseUrl() + "/admin/charges");
        assertThat(page.locator("#charge-table")).containsText(invoiceNumber);
        assertThat(page.locator("#charge-table")).containsText("Debitur " + debtor);
        // Dibayar column proves the webhook landed on this charge.
        assertThat(page.locator("#charge-table")).containsText("400.000");
    }

    @Test
    void search_narrowsByInvoiceNumberDebtorNameAndVa() {
        String debtorA = "DEB-" + unique();
        String debtorB = "DEB-" + unique();
        String type = "TYP" + unique();
        apiClient().debtor(debtorA, "Debitur " + debtorA, debtorA.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().debtor(debtorB, "Debitur " + debtorB, debtorB.toLowerCase() + "@example.com", "0812", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);

        var invoiceA = apiClient()
                .issueSingle(debtorA, type, 1_000_000, LocalDate.now(), LocalDate.now().plusDays(30));
        var invoiceB = apiClient()
                .issueSingle(debtorB, type, 2_000_000, LocalDate.now(), LocalDate.now().plusDays(30));
        String invoiceNumberA = invoiceA.path("invoiceNumber");
        String invoiceNumberB = invoiceB.path("invoiceNumber");
        String vaNumberA = apiClient().openCharge(invoiceA.path("id")).path("vaNumber");
        apiClient().openCharge(invoiceB.path("id"));

        page.navigate(baseUrl() + "/admin/charges");
        for (String q : new String[]{invoiceNumberA, "Debitur " + debtorA, vaNumberA}) {
            page.fill("#charge-search", q);
            page.click("#btn-search");
            assertThat(page.locator("#charge-table")).containsText(invoiceNumberA);
            assertThat(page.locator("#charge-table")).not().containsText(invoiceNumberB);
        }
    }
}
