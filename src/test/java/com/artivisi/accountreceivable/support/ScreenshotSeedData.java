package com.artivisi.accountreceivable.support;

import com.artivisi.accountreceivable.entity.UserRole;
import com.artivisi.accountreceivable.service.OperatorService;
import io.restassured.response.Response;

import java.time.LocalDate;
import java.util.List;

/**
 * Deterministic demo dataset driven through {@link ApiClient} (the open API + gateway webhook) plus
 * {@link OperatorService} to add a second user (no HTTP entry point). It exercises the real
 * lifecycle — issue, charge, cash application via signed webhook, write-off, credit note, dunning —
 * so the captured screens show authentic state, not hand-inserted rows.
 *
 * <p>Data is intentionally generic (no client names/credentials) per the engine's governance rule.
 * Amounts are whole rupiah. Dates are anchored to {@code today} so overdue/aging buckets are stable.
 */
public class ScreenshotSeedData {

    /** References to the seeded rows the screenshot/manual code navigates to by id. */
    public record Seeded(
            String openInvoiceId, String openInvoiceNumber,
            String overdueInvoiceId, String overdueInvoiceNumber,
            String partialInvoiceId, String partialInvoiceNumber,
            String paidInvoiceId, String paidInvoiceNumber,
            String writtenOffInvoiceId, String writtenOffInvoiceNumber,
            String installmentInvoiceId, String installmentInvoiceNumber,
            String creditNoteInvoiceId, String creditNoteInvoiceNumber,
            String dunningRunId,
            String statementDebtorCode, String statementDebtorId) {
    }

    private final ApiClient api;
    private final OperatorService operatorService;

    public ScreenshotSeedData(int port, String gatewaySecret, OperatorService operatorService) {
        this.api = new ApiClient(port, gatewaySecret);
        this.operatorService = operatorService;
    }

    public Seeded seed() {
        LocalDate today = LocalDate.now();

        // ---- Debtors -------------------------------------------------------
        api.debtor("DBT-001", "Budi Santoso", "budi.santoso@example.com", "0811000001", "ACTIVE");
        api.debtor("DBT-002", "Citra Lestari", "citra.lestari@example.com", "0811000002", "ACTIVE");
        Response statementDebtor =
                api.debtor("DBT-003", "Dewi Anggraini", "dewi.anggraini@example.com", "0811000003", "ACTIVE");
        api.debtor("DBT-004", "Koperasi Sejahtera", "admin@koperasi-sejahtera.example.com", "0811000004", "ACTIVE");
        api.debtor("DBT-005", "Eko Prasetyo", null, "0811000005", "ACTIVE"); // no email → dunning error row
        api.debtor("DBT-006", "Fitri Handayani", "fitri.handayani@example.com", "0811000006", "INACTIVE");

        // ---- Invoice types -------------------------------------------------
        api.invoiceType("IUR", "Iuran Bulanan", true);
        api.invoiceType("REG", "Biaya Registrasi", true);
        api.invoiceType("SEWA", "Sewa Fasilitas", true);
        api.invoiceType("ARSIP", "Tipe Nonaktif", false);

        // ---- Invoices ------------------------------------------------------
        // 1. OPEN, not yet due.
        Response open = api.issue("DBT-001", "IUR", today, today.plusDays(20),
                "Iuran bulan berjalan", List.of(ApiClient.line("Iuran bulanan", 1, 1_500_000)), null);

        // 2. OPEN + overdue.
        Response overdue = api.issue("DBT-002", "REG", today.minusDays(60), today.minusDays(30),
                "Registrasi tahun ajaran", List.of(ApiClient.line("Biaya registrasi", 1, 2_000_000)), null);

        // 3. PARTIALLY_PAID + overdue (400k of 1.5M via signed webhook).
        Response partial = api.issue("DBT-003", "IUR", today.minusDays(45), today.minusDays(15),
                "Iuran tertunggak", List.of(ApiClient.line("Iuran bulanan", 1, 1_500_000)), null);
        api.openCharge(partial.path("id"));
        api.webhook(partial.path("id"), "PAYMENT_RECEIVED", "PARTIALLY_PAID", 400_000, "BANK-P-001");

        // 4. PAID in full (via webhook).
        Response paid = api.issue("DBT-004", "SEWA", today.minusDays(10), today.plusDays(5),
                "Sewa aula", List.of(ApiClient.line("Sewa aula 2 hari", 2, 1_500_000)), null);
        api.openCharge(paid.path("id"));
        api.webhook(paid.path("id"), "PAYMENT_RECEIVED", "PAID", 3_000_000, "BANK-F-001");

        // 5. WRITTEN_OFF.
        Response writtenOff = api.issue("DBT-002", "IUR", today.minusDays(200), today.minusDays(170),
                "Piutang tak tertagih", List.of(ApiClient.line("Iuran lama", 1, 500_000)), null);
        api.writeOff(writtenOff.path("id"));

        // 6. INSTALLMENT: 6M in 3 installments; first paid → invoice PARTIALLY_PAID.
        Response installment = api.issue("DBT-001", "SEWA", today.minusDays(5), today.plusDays(85),
                "Sewa kios per triwulan",
                List.of(ApiClient.line("Sewa kios setahun", 1, 6_000_000)),
                List.of(ApiClient.installment(today.plusDays(25), 2_000_000),
                        ApiClient.installment(today.plusDays(55), 2_000_000),
                        ApiClient.installment(today.plusDays(85), 2_000_000)));
        String installmentInvoiceId = installment.path("id");
        api.openCharge(installmentInvoiceId);
        com.artivisi.accountreceivable.AbstractIntegrationTest.settleGatewayCharge(installmentInvoiceId);
        api.webhook(installmentInvoiceId, "CHARGE_PAID", "PAID", 2_000_000, "BANK-I-001");

        // 7. Credit note against an open invoice.
        Response creditNoted = api.issue("DBT-003", "REG", today.minusDays(3), today.plusDays(27),
                "Registrasi dengan koreksi", List.of(ApiClient.line("Biaya registrasi", 1, 1_200_000)), null);
        api.creditNote(creditNoted.path("id"), 200_000, "CORRECTION", "Koreksi kelebihan tagih");

        // 8. Overdue invoice for a debtor without email → dunning error row.
        api.issue("DBT-005", "REG", today.minusDays(50), today.minusDays(20),
                "Registrasi tertunggak", List.of(ApiClient.line("Biaya registrasi", 1, 750_000)), null);

        // ---- Dunning run over overdue receivables --------------------------
        String dunningRunId = api.dunningRun("EMAIL", 1).path("id");

        // ---- A second operator (no open API for user management) -----------
        if (operatorService.list().stream().noneMatch(u -> u.getUsername().equals("operator1"))) {
            operatorService.create("operator1", "Operator Piutang", UserRole.OPERATOR, "operator1-secret");
        }

        return new Seeded(
                open.path("id"), open.path("invoiceNumber"),
                overdue.path("id"), overdue.path("invoiceNumber"),
                partial.path("id"), partial.path("invoiceNumber"),
                paid.path("id"), paid.path("invoiceNumber"),
                writtenOff.path("id"), writtenOff.path("invoiceNumber"),
                installment.path("id"), installment.path("invoiceNumber"),
                creditNoted.path("id"), creditNoted.path("invoiceNumber"),
                dunningRunId,
                "DBT-003", statementDebtor.path("id"));
    }
}
