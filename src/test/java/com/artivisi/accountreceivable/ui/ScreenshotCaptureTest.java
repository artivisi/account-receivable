package com.artivisi.accountreceivable.ui;

import com.artivisi.accountreceivable.support.ScreenshotSeedData;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds the deterministic demo dataset, then walks every admin screen and writes a full-page PNG
 * into {@code docs/user-manual/screenshots}. The images embedded in the Indonesian user manual are
 * produced here, so they always reflect real, seeded application state.
 *
 * <p>This is not a smoke test — behaviour is asserted by the {@code *UiTest} classes. It only
 * guarantees each screen renders and a screenshot is written. Run it explicitly to refresh the
 * manual images:
 * <pre>mvn -Dtest=ScreenshotCaptureTest test</pre>
 */
class ScreenshotCaptureTest extends PlaywrightTestBase {

    @Test
    void captureAllScreens() {
        truncateAllExceptUsers();
        ScreenshotSeedData.Seeded s = seedDemoData();

        Path dir = screenshotsDir();

        // Public login screen (before authenticating).
        page.navigate(baseUrl() + "/login");
        screenshot(dir, "01-login");

        loginAsAdmin();

        capture(dir, "02-dashboard", "/admin");
        capture(dir, "03-debtors", "/admin/debtors");
        capture(dir, "04-debtor-form", "/admin/debtors/new");
        capture(dir, "21-debtor-detail", "/admin/debtors/" + s.statementDebtorId());
        capture(dir, "05-invoice-types", "/admin/invoice-types");
        capture(dir, "06-invoice-type-form", "/admin/invoice-types/new");
        capture(dir, "07-invoices", "/admin/invoices");
        capture(dir, "08-invoice-form", "/admin/invoices/new");
        capture(dir, "09-invoice-detail", "/admin/invoices/" + s.partialInvoiceId());
        capture(dir, "10-invoice-installment-detail", "/admin/invoices/" + s.installmentInvoiceId());
        capture(dir, "11-charges", "/admin/charges");
        capture(dir, "12-cash-applications", "/admin/cash-applications");
        capture(dir, "13-dunning", "/admin/dunning");
        capture(dir, "14-dunning-detail", "/admin/dunning/" + s.dunningRunId());
        capture(dir, "15-aging", "/admin/reports/aging");
        capture(dir, "16-recap", "/admin/reports/recap");
        capture(dir, "17-statement", "/admin/reports/statement?debtorCode=" + s.statementDebtorCode());
        capture(dir, "18-audit", "/admin/audit");
        capture(dir, "19-operators", "/admin/operators");
        capture(dir, "20-bulk-upload", "/admin/bulk-uploads");
    }

    private void capture(Path dir, String name, String path) {
        navigateTo(path);
        screenshot(dir, name);
        assertThat(Files.exists(dir.resolve(name + ".png")))
                .as("screenshot written: " + name)
                .isTrue();
    }
}
