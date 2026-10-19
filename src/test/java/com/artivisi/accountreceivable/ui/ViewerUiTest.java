package com.artivisi.accountreceivable.ui;

import com.artivisi.accountreceivable.entity.UserRole;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.FormData;
import com.microsoft.playwright.options.RequestOptions;
import org.junit.jupiter.api.Test;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * VIEWER reads and changes nothing. The refusal is checked on the server with a valid CSRF token,
 * so a 403 here can only come from the role: a missing token would be refused for any role and
 * would prove nothing.
 */
class ViewerUiTest extends PlaywrightTestBase {

    @Test
    void viewerSeesThePages_butNoAction() {
        loginAsViewer();

        page.navigate(baseUrl() + "/admin/invoices");
        assertThat(page.locator("#nav-invoices")).isVisible();
        assertThat(page.locator("#btn-new-invoice")).hasCount(0);
        assertThat(page.locator("#nav-bulk-upload")).hasCount(0);
        assertThat(page.locator("#nav-operators")).hasCount(0);

        page.navigate(baseUrl() + "/admin/dunning");
        assertThat(page.locator("#dunning-form")).hasCount(0);
    }

    @Test
    void viewerIsRefusedEveryChange_evenWithAValidToken() {
        loginAsViewer();
        page.navigate(baseUrl() + "/admin/invoices");

        APIResponse writeOff = postWithToken("/admin/invoices/does-not-exist/write-off");
        assertThat(writeOff.status()).isEqualTo(403);

        assertThat(page.request().get(baseUrl() + "/admin/invoices/new").status()).isEqualTo(403);
        assertThat(page.request().get(baseUrl() + "/admin/bulk-uploads").status()).isEqualTo(403);
    }

    @Test
    void operatorWithTheSameRequest_isNotRefused() {
        loginAsAdmin();
        page.navigate(baseUrl() + "/admin/invoices");

        APIResponse writeOff = postWithToken("/admin/invoices/does-not-exist/write-off");
        assertThat(writeOff.status()).isNotEqualTo(403);
    }

    @Test
    void viewerCanStillChangeOwnPassword() {
        String username = loginAsViewer();
        page.navigate(baseUrl() + "/change-password");
        page.fill("#currentPassword", "rahasia-" + username);
        page.fill("#newPassword", "baru-rahasia-" + username);
        page.fill("#confirmPassword", "baru-rahasia-" + username);
        page.click("#btn-change-password");

        page.waitForURL("**/admin");
    }

    private String loginAsViewer() {
        String username = "viewer" + unique();
        String password = "rahasia-" + username;
        operatorService.create(username, "Viewer " + username, UserRole.VIEWER, password);
        login(username, password);
        return username;
    }

    private APIResponse postWithToken(String path) {
        String token = page.locator("input[name=_csrf]").first().inputValue();
        return page.request().post(baseUrl() + path, RequestOptions.create()
                .setForm(FormData.create().set("_csrf", token).set("reason", "test"))
                .setMaxRedirects(0));
    }
}
