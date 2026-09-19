package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Debtor registry screens: create, duplicate-code validation, edit. */
class DebtorUiTest extends PlaywrightTestBase {

    @BeforeEach
    void authenticate() {
        loginAsAdmin();
    }

    @Test
    void createDebtor_throughForm_appearsInTable() {
        String code = "DEB-" + unique();
        page.navigate(baseUrl() + "/admin/debtors/new");
        page.fill("#debtor-code", code);
        page.fill("#debtor-name", "Debitur Uji " + code);
        page.fill("#debtor-email", code.toLowerCase() + "@example.com");
        page.fill("#debtor-phone", "0812345678");
        page.selectOption("#debtor-status", "ACTIVE");
        page.click("#btn-save-debtor");

        page.waitForURL("**/admin/debtors");
        assertThat(page.locator("#flash-msg")).containsText("berhasil dibuat");
        assertThat(page.locator("#debtor-row-" + code)).isVisible();
        assertThat(page.locator("#debtor-row-" + code)).containsText("AKTIF");
    }

    @Test
    void duplicateCode_showsFormError() {
        String code = "DEB-" + unique();
        apiClient().debtor(code, "Sudah Ada", "dup@example.com", "0811", "ACTIVE");

        page.navigate(baseUrl() + "/admin/debtors/new");
        page.fill("#debtor-code", code);
        page.fill("#debtor-name", "Duplikat");
        page.selectOption("#debtor-status", "ACTIVE");
        page.click("#btn-save-debtor");

        page.waitForURL("**/admin/debtors/new");
        assertThat(page.locator("#form-error")).isVisible();
    }

    @Test
    void editDebtor_changesStatusToInactive() {
        String code = "DEB-" + unique();
        apiClient().debtor(code, "Untuk Diedit", "edit@example.com", "0811", "ACTIVE");

        page.navigate(baseUrl() + "/admin/debtors");
        page.click("#edit-debtor-" + code);
        page.waitForURL("**/edit");
        page.selectOption("#debtor-status", "INACTIVE");
        page.click("#btn-save-debtor");

        page.waitForURL("**/admin/debtors");
        assertThat(page.locator("#debtor-row-" + code)).containsText("NONAKTIF");
    }

    @Test
    void debtorDetail_showsStatsLedgerAndOpenInvoices() {
        String code = "DEB-" + unique();
        String type = "TYP" + unique();
        apiClient().debtor(code, "Pelanggan Detail " + code, code.toLowerCase() + "@example.com", "0812000111", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        String invoiceNumber = apiClient()
                .issueSingle(code, type, 1_000_000, LocalDate.now().minusDays(20), LocalDate.now().minusDays(5))
                .path("invoiceNumber");

        page.navigate(baseUrl() + "/admin/debtors");
        page.click("#debtor-detail-link-" + code);
        page.waitForURL("**/admin/debtors/**");

        assertThat(page.locator("#debtor-code")).hasText(code);
        assertThat(page.locator("#debtor-status")).hasText("AKTIF");
        assertThat(page.locator("#debtor-outstanding")).containsText("1.000.000");
        assertThat(page.locator("#debtor-overdue")).containsText("1.000.000");
        assertThat(page.locator("#debtor-ledger-table")).containsText(invoiceNumber);
        assertThat(page.locator("#debtor-open-invoices-table")).containsText(invoiceNumber);
        assertThat(page.locator("#debtor-open-invoices-table")).containsText("MENUNGGAK");
    }

    @Test
    void list_searchAndPaginate() {
        String code = "DEB-" + unique();
        apiClient().debtor(code, "Pencarian " + code, code.toLowerCase() + "@example.com", "0813", "ACTIVE");

        page.navigate(baseUrl() + "/admin/debtors?q=" + code);

        assertThat(page.locator("#debtor-table")).containsText(code);
        assertThat(page.locator("#pagination-info")).isVisible();
    }
}
