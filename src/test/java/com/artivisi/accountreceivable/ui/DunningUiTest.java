package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Dunning run launched from the UI over an overdue receivable, then the run detail. */
class DunningUiTest extends PlaywrightTestBase {

    @BeforeEach
    void authenticate() {
        loginAsAdmin();
    }

    @Test
    void runDunning_fromForm_sendsReminderForOverdueInvoice() {
        String debtor = "DEB-" + unique();
        String type = "TYP" + unique();
        apiClient().debtor(debtor, "Debitur " + debtor, debtor.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        String invoiceNumber = apiClient()
                .issueSingle(debtor, type, 900_000, LocalDate.now().minusDays(40), LocalDate.now().minusDays(10))
                .path("invoiceNumber");

        page.navigate(baseUrl() + "/admin/dunning");
        page.selectOption("#dunning-channel", "EMAIL");
        page.fill("#dunning-min-days", "1");
        page.click("#btn-run-dunning");

        // Redirects to the run detail.
        page.waitForURL("**/admin/dunning/**");
        assertThat(page.locator("#dunning-run-channel")).hasText("EMAIL");
        assertThat(page.locator("#reminder-row-" + invoiceNumber)).isVisible();
        assertThat(page.locator("#reminder-row-" + invoiceNumber)).containsText("TERKIRIM");
    }
}
