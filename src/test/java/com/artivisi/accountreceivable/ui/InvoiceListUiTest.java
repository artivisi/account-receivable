package com.artivisi.accountreceivable.ui;

import com.artivisi.accountreceivable.support.ApiClient;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Faktur list: status-tab filter and search narrow the visible rows. */
class InvoiceListUiTest extends PlaywrightTestBase {

    private String debtorA;
    private String debtorB;
    private String type;

    @BeforeEach
    void setUp() {
        debtorA = "DEB-" + unique();
        debtorB = "DEB-" + unique();
        type = "TYP" + unique();
        apiClient().debtor(debtorA, "Debitur " + debtorA, debtorA.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().debtor(debtorB, "Debitur " + debtorB, debtorB.toLowerCase() + "@example.com", "0812", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        loginAsAdmin();
    }

    @Test
    void statusTab_narrowsToWrittenOff() {
        String numberOpen = apiClient()
                .issueSingle(debtorA, type, 100_000, LocalDate.now(), LocalDate.now().plusDays(30))
                .path("invoiceNumber");
        Response toWriteOff = apiClient()
                .issueSingle(debtorB, type, 200_000, LocalDate.now(), LocalDate.now().plusDays(30));
        String idWrittenOff = toWriteOff.path("id");
        String numberWrittenOff = toWriteOff.path("invoiceNumber");
        apiClient().writeOff(idWrittenOff);

        page.navigate(baseUrl() + "/admin/invoices");
        assertThat(page.locator("#invoice-table")).containsText(numberOpen);
        assertThat(page.locator("#invoice-table")).containsText(numberWrittenOff);

        page.click("#tab-status-writtenoff");
        page.waitForURL("**status=WRITTEN_OFF**");
        assertThat(page.locator("#invoice-table")).containsText(numberWrittenOff);
        assertThat(page.locator("#invoice-table")).not().containsText(numberOpen);
    }

    @Test
    void search_narrowsByDebtorCode() {
        String numberA = apiClient()
                .issueSingle(debtorA, type, 150_000, LocalDate.now(), LocalDate.now().plusDays(30))
                .path("invoiceNumber");
        String numberB = apiClient()
                .issueSingle(debtorB, type, 250_000, LocalDate.now(), LocalDate.now().plusDays(30))
                .path("invoiceNumber");

        page.navigate(baseUrl() + "/admin/invoices");
        page.fill("#invoice-search", debtorA);
        page.click("#btn-search");
        page.waitForURL("**/admin/invoices**");

        assertThat(page.locator("#invoice-table")).containsText(numberA);
        assertThat(page.locator("#invoice-table")).not().containsText(numberB);
    }

    /**
     * The MENUNGGAK (overdue) filter is derived in SQL from the denormalized earliest-unpaid-due-date
     * column. Its tricky case is an installment invoice: overdue when ANY unpaid installment is past
     * due, even though the invoice header may be fine. Scoped by ?q=debtor so it is robust against
     * data other tests leave in the shared DB.
     */
    @Test
    void menunggakFilter_includesInstallmentWithOverdueInstallment() {
        LocalDate today = LocalDate.now();
        // Installment invoice whose FIRST installment is already past due -> overdue.
        String overdueInstallment = apiClient().issue(debtorA, type, today.minusDays(40), today.plusDays(50),
                "Cicilan menunggak",
                List.of(ApiClient.line("Paket", 1, 3_000_000)),
                List.of(ApiClient.installment(today.minusDays(10), 1_000_000),
                        ApiClient.installment(today.plusDays(20), 1_000_000),
                        ApiClient.installment(today.plusDays(50), 1_000_000)))
                .path("invoiceNumber");
        // Single-payment invoice not yet due -> not overdue.
        String notDue = apiClient()
                .issueSingle(debtorA, type, 500_000, today, today.plusDays(30))
                .path("invoiceNumber");

        page.navigate(baseUrl() + "/admin/invoices?status=MENUNGGAK&q=" + debtorA);
        assertThat(page.locator("#invoice-table")).containsText(overdueInstallment);
        assertThat(page.locator("#invoice-table")).not().containsText(notDue);

        // Without the filter, both are visible.
        page.navigate(baseUrl() + "/admin/invoices?q=" + debtorA);
        assertThat(page.locator("#invoice-table")).containsText(overdueInstallment);
        assertThat(page.locator("#invoice-table")).containsText(notDue);
    }

    /** Server-side pagination: 21 invoices for one debtor span two 20-row pages. */
    @Test
    void pagination_spansPagesForOneDebtor() {
        LocalDate today = LocalDate.now();
        String first = null;
        for (int i = 0; i < 21; i++) {
            String number = apiClient()
                    .issueSingle(debtorB, type, 100_000, today, today.plusDays(30))
                    .path("invoiceNumber");
            if (first == null) {
                first = number; // lowest invoice number -> sorts last (newest-first) -> lands on page 2
            }
        }

        page.navigate(baseUrl() + "/admin/invoices?q=" + debtorB);
        assertThat(page.locator("#pagination-info")).containsText("dari 21");
        assertThat(page.locator("#page-indicator")).hasText("1 / 2");
        assertThat(page.locator("#invoice-table")).not().containsText(first);

        page.click("#btn-next-page");
        page.waitForURL("**page=1**");
        assertThat(page.locator("#page-indicator")).hasText("2 / 2");
        assertThat(page.locator("#invoice-table")).containsText(first);
    }
}
