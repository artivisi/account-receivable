package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Operator (application user) management: create and enable/disable. ADMIN-only screen. */
class OperatorUiTest extends PlaywrightTestBase {

    @BeforeEach
    void authenticate() {
        loginAsAdmin();
    }

    @Test
    void createOperator_thenToggleEnabled() {
        String username = "op" + unique();
        page.navigate(baseUrl() + "/admin/operators");
        page.fill("#operator-username", username);
        page.fill("#operator-display-name", "Operator " + username);
        page.selectOption("#operator-role", "OPERATOR");
        page.fill("#operator-password", "rahasia-" + username);
        page.click("#btn-create-operator");

        page.waitForURL("**/admin/operators");
        assertThat(page.locator("#flash-msg")).containsText("berhasil dibuat");
        assertThat(page.locator("#operator-row-" + username)).containsText("AKTIF");

        // Disable the operator.
        page.click("#btn-toggle-operator-" + username);
        page.waitForURL("**/admin/operators");
        assertThat(page.locator("#operator-row-" + username)).containsText("NONAKTIF");
    }
}
