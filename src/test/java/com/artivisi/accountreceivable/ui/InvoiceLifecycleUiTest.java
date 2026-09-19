package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Single-payment invoice lifecycle through the UI: issue, open charge, write off, credit note. */
class InvoiceLifecycleUiTest extends PlaywrightTestBase {

    private String debtor;
    private String type;

    @BeforeEach
    void setUp() {
        debtor = "DEB-" + unique();
        type = "TYP" + unique();
        apiClient().debtor(debtor, "Debitur " + debtor, debtor.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        loginAsAdmin();
    }

    @Test
    void issueSingleInvoice_throughForm_showsOpenDetail() {
        page.navigate(baseUrl() + "/admin/invoices/new");
        page.fill("#invoice-debtor-search", debtor);
        page.click("#debtor-option-" + debtor);
        page.fill("#invoice-type-search", type);
        page.click("#type-option-" + type);
        page.fill("#line-description-1", "Layanan bulan ini");
        page.fill("#line-quantity-1", "1");
        page.fill("#line-unit-amount-1", "1250000");
        page.click("#btn-issue-invoice");

        page.waitForURL("**/admin/invoices/**");
        assertThat(page.locator("#invoice-status")).hasText("OPEN");
        assertThat(page.locator("#invoice-debtor")).hasText(debtor);
        assertThat(page.locator("#invoice-lines-table")).containsText("Layanan bulan ini");
        assertThat(page.locator("#invoice-outstanding")).containsText("1.250.000");
    }

    @Test
    void openCharge_showsFlashMessage() {
        String id = apiClient().issueSingle(debtor, type, 500_000, LocalDate.now(), LocalDate.now().plusDays(30))
                .path("id");
        page.navigate(baseUrl() + "/admin/invoices/" + id);
        page.click("#btn-open-charge");
        assertThat(page.locator("#flash-msg")).containsText("Charge berhasil dibuka");
    }

    @Test
    void writeOff_setsStatusWrittenOff_andHidesActions() {
        String id = apiClient().issueSingle(debtor, type, 300_000, LocalDate.now(), LocalDate.now().plusDays(30))
                .path("id");
        page.navigate(baseUrl() + "/admin/invoices/" + id);
        // The reason is a required field — write-off is irreversible, so the why is recorded with it.
        page.onDialog(dialog -> dialog.accept());
        page.fill("#input-write-off-reason", "Debitur tidak dapat dihubungi");
        page.click("#btn-write-off");
        assertThat(page.locator("#invoice-status")).hasText("WRITE-OFF");
        // Non-collectible: every action the backend would reject is gone.
        assertThat(page.locator("#btn-open-charge")).isHidden();
        assertThat(page.locator("#btn-write-off")).isHidden();
        assertThat(page.locator("#credit-note-form")).isHidden();
    }

    @Test
    void paidInvoice_hidesCollectionActions() {
        String id = apiClient().issueSingle(debtor, type, 500_000, LocalDate.now(), LocalDate.now().plusDays(30))
                .path("id");
        apiClient().openCharge(id);
        apiClient().webhook(id, "PAYMENT_RECEIVED", "PAID", 500_000, "BANK-" + unique());

        page.navigate(baseUrl() + "/admin/invoices/" + id);
        assertThat(page.locator("#invoice-status")).hasText("LUNAS");
        assertThat(page.locator("#btn-open-charge")).isHidden();
        assertThat(page.locator("#btn-write-off")).isHidden();
        assertThat(page.locator("#credit-note-form")).isHidden();
    }

    @Test
    void issueCreditNote_showsFlashMessage() {
        String id = apiClient().issueSingle(debtor, type, 800_000, LocalDate.now(), LocalDate.now().plusDays(30))
                .path("id");
        page.navigate(baseUrl() + "/admin/invoices/" + id);
        page.fill("#credit-note-amount", "150000");
        page.fill("#credit-note-reason", "Koreksi tagihan");
        page.click("#btn-issue-credit-note");
        assertThat(page.locator("#flash-msg")).containsText("Nota kredit berhasil diterbitkan");
    }
}
