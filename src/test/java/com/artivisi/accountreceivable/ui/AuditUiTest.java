package com.artivisi.accountreceivable.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** The audit log records every state-changing admin/API action. */
class AuditUiTest extends PlaywrightTestBase {

    private String debtor;
    private String type;

    @BeforeEach
    void setUp() {
        debtor = "DEB-" + unique();
        type = "TYP" + unique();
        apiClient().debtor(debtor, "Debitur " + debtor, debtor.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);
        apiClient().issueSingle(debtor, type, 1_000_000, LocalDate.now(), LocalDate.now().plusDays(30));
        loginAsAdmin();
    }

    @Test
    void invoiceActions_recordedInAuditLog() {
        page.navigate(baseUrl() + "/admin/audit");
        assertThat(page.locator("#audit-table")).containsText("INVOICE_ISSUED");
    }
}
