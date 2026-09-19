package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Invoice-type registry screens: create with GL template mapping, edit active flag. */
class InvoiceTypeUiTest extends PlaywrightTestBase {

    @BeforeEach
    void authenticate() {
        loginAsAdmin();
    }

    @Test
    void createInvoiceType_throughForm() {
        String code = "TYP" + unique();
        page.navigate(baseUrl() + "/admin/invoice-types/new");
        page.fill("#type-code", code);
        page.fill("#type-name", "Tipe " + code);
        page.click("#btn-save-type");

        page.waitForURL("**/admin/invoice-types");
        assertThat(page.locator("#flash-msg")).containsText("berhasil dibuat");
        assertThat(page.locator("#type-row-" + code)).isVisible();
        assertThat(page.locator("#type-row-" + code)).containsText("AKTIF");
    }

    @Test
    void editInvoiceType_deactivate() {
        String code = "TYP" + unique();
        apiClient().invoiceType(code, "Untuk Dinonaktifkan", true);

        page.navigate(baseUrl() + "/admin/invoice-types");
        page.click("#edit-type-" + code);
        page.waitForURL("**/edit");
        // Uncheck 'active'.
        if (page.locator("#type-active").isChecked()) {
            page.locator("#type-active").uncheck();
        }
        page.click("#btn-save-type");

        page.waitForURL("**/admin/invoice-types");
        assertThat(page.locator("#type-row-" + code)).containsText("NONAKTIF");
    }
}
