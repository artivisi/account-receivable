package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/**
 * Typeahead debtor picker (replaces the load-everything select) on the invoice-new form:
 * search narrows to matches, picking sets the submitted debtorCode, editing invalidates the pick.
 */
class DebtorPickerUiTest extends PlaywrightTestBase {

    private String match;
    private String other;
    private String type;

    @BeforeEach
    void setUp() {
        match = "PICK-" + unique();
        other = "MISS-" + unique();
        type = "TYP" + unique();
        apiClient().debtor(match, "Pelanggan Cocok " + match, match.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().debtor(other, "Pelanggan Lain " + other, other.toLowerCase() + "@example.com", "0812", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        loginAsAdmin();
    }

    @Test
    void search_narrowsToMatch_pickSetsHiddenCode_editClearsIt() {
        page.navigate(baseUrl() + "/admin/invoices/new");

        // Typing a code shows only the matching debtor's option.
        page.fill("#invoice-debtor-search", match);
        assertThat(page.locator("#debtor-option-" + match)).isVisible();
        assertThat(page.locator("#debtor-option-" + other)).hasCount(0);

        // Picking fills the hidden debtorCode and shows the label in the search field.
        page.click("#debtor-option-" + match);
        assertThat(page.locator("#invoice-debtor")).hasValue(match);
        assertThat(page.locator("#invoice-debtor-search")).hasValue(match + " · Pelanggan Cocok " + match);

        // Editing the search text invalidates the prior pick (hidden code cleared).
        page.fill("#invoice-debtor-search", "x");
        assertThat(page.locator("#invoice-debtor")).hasValue("");
    }
}
