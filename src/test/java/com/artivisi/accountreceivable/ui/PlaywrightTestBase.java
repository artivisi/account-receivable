package com.artivisi.accountreceivable.ui;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.service.OperatorService;
import com.artivisi.accountreceivable.support.ApiClient;
import com.artivisi.accountreceivable.support.ScreenshotSeedData;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared Playwright harness for the admin UI. Extends {@link AbstractIntegrationTest} so every UI
 * test gets the full app on a random port with the same PostgreSQL container and gateway stub the
 * RestAssured suites use. A fresh {@link BrowserContext}/{@link Page} is created per test.
 *
 * <p>Locator convention for the whole UI suite: address elements by their stable {@code id}
 * (e.g. {@code #invoice-form}, {@code #debtor-row-DBT-001}). No CSS-class or positional-xpath
 * locators — the templates carry ids specifically so the tests stay decoupled from styling.
 *
 * <p>System properties: {@code -Dplaywright.headless=false} to watch a run,
 * {@code -Dplaywright.slowmo=250} to slow it down.
 */
public abstract class PlaywrightTestBase extends AbstractIntegrationTest {

    protected static final String ADMIN_USER = "admin";
    protected static final String ADMIN_PASSWORD = "admin-secret";

    private static final boolean HEADLESS =
            Boolean.parseBoolean(System.getProperty("playwright.headless", "true"));
    private static final int SLOW_MO =
            Integer.parseInt(System.getProperty("playwright.slowmo", "0"));

    private static Playwright playwright;
    protected static Browser browser;

    protected BrowserContext context;
    protected Page page;

    @Autowired
    protected OperatorService operatorService;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private static final AtomicInteger SEQ = new AtomicInteger();

    /** Unique short suffix for functional tests that create their own rows in the shared DB. */
    protected String unique() {
        return Integer.toString(SEQ.incrementAndGet());
    }

    /**
     * Wipe all AR data (everything except {@code app_user}, which holds the bootstrap admin that is
     * only seeded at startup). Used by the screenshot run so aggregate screens reflect the demo data
     * alone. RESTART IDENTITY resets running-number sequences for stable invoice numbers.
     */
    protected void truncateAllExceptUsers() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE notification_outbox, invoice_type_va_code, running_number,
                    invoice_line, installment, payment_schedule, credit_note,
                    cash_application_line, cash_application, charge_cancellation, charge,
                    dunning_reminder, dunning_run,
                    bulk_upload_error, bulk_upload_batch, audit_event,
                    invoice, invoice_type, debtor
                RESTART IDENTITY CASCADE""");
    }

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                .setHeadless(HEADLESS)
                .setSlowMo(SLOW_MO));
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) {
            browser.close();
        }
        if (playwright != null) {
            playwright.close();
        }
    }

    @BeforeEach
    void openContext() {
        context = browser.newContext(new Browser.NewContextOptions()
                .setViewportSize(1366, 900)
                .setLocale("id-ID"));
        page = context.newPage();
        page.setDefaultTimeout(10_000);
        // Kill animations/transitions so screenshots are deterministic.
        page.addInitScript("""
                () => {
                  const style = document.createElement('style');
                  style.innerHTML = '*, *::before, *::after {' +
                    'transition-duration: 0s !important; animation-duration: 0s !important; }';
                  document.documentElement.appendChild(style);
                }""");
    }

    @AfterEach
    void closeContext() {
        if (context != null) {
            context.close();
        }
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    protected void navigateTo(String path) {
        page.navigate(baseUrl() + path);
        page.waitForLoadState(LoadState.NETWORKIDLE);
    }

    protected void login(String username, String password) {
        // The bootstrap admin (and freshly-created operators) carry a forced first-login password
        // change, which ForcePasswordChangeFilter redirects to /change-password. These UI tests
        // exercise the AR screens, not that ceremony, so clear the flag as a test precondition.
        jdbcTemplate.update("UPDATE app_user SET must_change_password = false WHERE username = ?", username);
        page.navigate(baseUrl() + "/login");
        page.fill("#username", username);
        page.fill("#password", password);
        page.click("#btn-login");
        page.waitForURL("**/admin");
    }

    protected void loginAsAdmin() {
        login(ADMIN_USER, ADMIN_PASSWORD);
    }

    /** API/webhook client against this test instance, for functional-test preconditions. */
    protected ApiClient apiClient() {
        return new ApiClient(port, GATEWAY_CLIENT_SECRET);
    }

    /** Seed the deterministic demo dataset through the API + service beans. */
    protected ScreenshotSeedData.Seeded seedDemoData() {
        return new ScreenshotSeedData(port, GATEWAY_CLIENT_SECRET, operatorService).seed();
    }

    protected void screenshot(Path dir, String name) {
        dir.toFile().mkdirs();
        page.waitForLoadState(LoadState.NETWORKIDLE);
        page.screenshot(new Page.ScreenshotOptions()
                .setPath(dir.resolve(name + ".png"))
                .setFullPage(true));
    }

    protected static Path screenshotsDir() {
        return Paths.get("docs", "user-manual", "screenshots");
    }
}
