package com.artivisi.accountreceivable.ui;

import com.microsoft.playwright.options.FilePayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

/** Bulk receivable CSV upload and the result summary. */
class BulkUploadUiTest extends PlaywrightTestBase {

    @BeforeEach
    void authenticate() {
        loginAsAdmin();
    }

    @Test
    void uploadCsv_createsInvoices_andShowsResult() {
        String debtor = "DEB-" + unique();
        String type = "TYP" + unique();
        apiClient().debtor(debtor, "Debitur " + debtor, debtor.toLowerCase() + "@example.com", "0811", "ACTIVE");
        apiClient().invoiceType(type, "Tipe " + type, true);

        LocalDate today = LocalDate.now();
        String csv = "debtorCode,invoiceTypeCode,issueDate,dueDate,amount,description\n"
                + debtor + "," + type + "," + today + "," + today.plusDays(30) + ",500000,Tagihan A\n"
                + debtor + "," + type + "," + today + "," + today.plusDays(30) + ",750000,Tagihan B\n";

        page.navigate(baseUrl() + "/admin/bulk-uploads");
        page.setInputFiles("#bulk-upload-file", new FilePayload(
                "receivables.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8)));
        page.click("#btn-bulk-upload");

        assertThat(page.locator("#bulk-upload-result")).isVisible();
        assertThat(page.locator("#bulk-result-success")).hasText("2");
        assertThat(page.locator("#bulk-result-errors")).hasText("0");
    }
}
