package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Dedicated cash-application screen. Preconditions (issue → open charge → signed payment webhook)
 * are set up via the API/webhook, matching the flow in {@link CollectionUiTest}; this test asserts
 * the resulting cash application row renders on its own page with the right allocation + status.
 */
class CashApplicationUiTest extends PlaywrightTestBase {

    @BeforeEach
    void authenticate() {
        loginAsAdmin();
    }

    @Test
    void cashApplication_appearsWithAllocationAndStatus_afterWebhook() {
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

        page.navigate(baseUrl() + "/admin/cash-applications");
        assertThat(page.locator("#cash-application-row-" + bankRef)).isVisible();
        assertThat(page.locator("#cash-application-row-" + bankRef)).containsText(bankRef);
        assertThat(page.locator("#cash-application-row-" + bankRef)).containsText(invoiceNumber);
        assertThat(page.locator("#cash-application-row-" + bankRef)).containsText("Debitur " + debtor);
        assertThat(page.locator("#cash-application-row-" + bankRef)).containsText("APPLIED");
    }
}
