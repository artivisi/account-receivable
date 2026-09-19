package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Login, logout, and access control on the admin UI. */
class AuthUiTest extends PlaywrightTestBase {

    @Test
    void unauthenticatedAdminAccess_redirectsToLogin() {
        page.navigate(baseUrl() + "/admin");
        assertThat(page).hasURL(java.util.regex.Pattern.compile(".*/login.*"));
        assertThat(page.locator("#login-form")).isVisible();
    }

    @Test
    void invalidCredentials_showError() {
        page.navigate(baseUrl() + "/login");
        page.fill("#username", "admin");
        page.fill("#password", "wrong-password");
        page.click("#btn-login");
        assertThat(page.locator("#login-error")).isVisible();
    }

    @Test
    void validLogin_landsOnDashboard_withAdminNav() {
        loginAsAdmin();
        assertThat(page.locator("#page-title")).hasText("Dashboard Umur Piutang");
        assertThat(page.locator("#current-user")).hasText("admin");
        // Operators nav is ADMIN-only and must be present for the bootstrap admin.
        assertThat(page.locator("#nav-operators")).isVisible();
    }

    @Test
    void logout_returnsToLoginWithNotice() {
        loginAsAdmin();
        page.click("#btn-logout");
        page.waitForURL("**/login?logout");
        assertThat(page.locator("#login-logout")).isVisible();
    }
}
