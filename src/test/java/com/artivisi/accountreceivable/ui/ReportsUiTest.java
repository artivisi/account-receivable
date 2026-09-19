package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Aging, recap-by-type, and per-debtor statement reports. */
class ReportsUiTest extends PlaywrightTestBase {

    private String debtor;
    private String type;
    private String invoiceNumber;

    @BeforeEach
    void setUp() {
        debtor = "DEB-" + unique();
        type = "TYP" + unique();
        apiClient().debtor(debtor, "Debitur " + debtor, debtor.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        // One overdue open invoice so it lands in an aging bucket and the statement.
        invoiceNumber = apiClient()
                .issueSingle(debtor, type, 1_100_000, LocalDate.now().minusDays(50), LocalDate.now().minusDays(20))
                .path("invoiceNumber");
        loginAsAdmin();
    }

    @Test
    void agingReport_rendersBucketsAndTotal() {
        page.navigate(baseUrl() + "/admin/reports/aging");
        assertThat(page.locator("#aging-table")).isVisible();
        assertThat(page.locator("#aging-total")).not().isEmpty();
    }

    @Test
    void recapReport_showsRowForType() {
        page.navigate(baseUrl() + "/admin/reports/recap");
        assertThat(page.locator("#recap-row-" + type)).isVisible();
    }

    @Test
    void statement_forDebtor_listsInvoice() {
        page.navigate(baseUrl() + "/admin/reports/statement");
        page.fill("#statement-debtor-search", debtor);
        page.click("#debtor-option-" + debtor);
        page.click("#btn-show-statement");
        page.waitForURL("**/statement**");
        assertThat(page.locator("#statement-card")).isVisible();
        assertThat(page.locator("#statement-row-" + invoiceNumber)).isVisible();
    }
}
