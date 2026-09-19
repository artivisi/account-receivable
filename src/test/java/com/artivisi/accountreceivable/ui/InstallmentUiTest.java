package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Installment invoice: issue a schedule through the form, then open the plan's single charge. */
class InstallmentUiTest extends PlaywrightTestBase {

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
    void issueInstallmentInvoice_thenOpenThePlanCharge() {
        LocalDate today = LocalDate.now();
        page.navigate(baseUrl() + "/admin/invoices/new");
        page.fill("#invoice-debtor-search", debtor);
        page.click("#debtor-option-" + debtor);
        page.fill("#invoice-type-search", type);
        page.click("#type-option-" + type);
        page.fill("#line-description-1", "Paket tahunan");
        page.fill("#line-quantity-1", "1");
        page.fill("#line-unit-amount-1", "6000000");

        page.locator("#installment").check();
        page.fill("#installment-due-date-1", today.plusDays(30).toString());
        page.fill("#installment-amount-1", "2000000");
        page.fill("#installment-due-date-2", today.plusDays(60).toString());
        page.fill("#installment-amount-2", "2000000");
        page.fill("#installment-due-date-3", today.plusDays(90).toString());
        page.fill("#installment-amount-3", "2000000");
        page.click("#btn-issue-invoice");

        page.waitForURL("**/admin/invoices/**");
        assertThat(page.locator("#installments-table")).isVisible();
        assertThat(page.locator("#installment-row-1")).containsText("2.000.000");
        assertThat(page.locator("#installment-row-2")).isVisible();
        assertThat(page.locator("#installment-row-3")).isVisible();

        // One charge for the whole plan, opened from the invoice, worth the first leg.
        page.click("#btn-open-charge");
        assertThat(page.locator("#flash-msg")).containsText("Charge berhasil dibuka");
    }
}
