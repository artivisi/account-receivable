package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

class BulkUploadIntegrationTest extends AbstractIntegrationTest {

    private void seed(String debtor, String type) {
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    @Test
    void upload_postsGoodRows_andReportsBadRowsWithLineNumbers() {
        seed("bulk-acme", "bulk-type");
        String csv = """
                debtorCode,invoiceTypeCode,issueDate,dueDate,amount,description
                bulk-acme,bulk-type,2026-06-01,2026-07-01,500000,Tuition June
                bulk-acme,bulk-type,2026-06-01,2026-07-01,750000,Tuition July
                no-such,bulk-type,2026-06-01,2026-07-01,100000,Unknown debtor
                bulk-acme,bulk-type,2026-06-01,2026-07-01,abc,Bad amount
                """;

        given().multiPart("file", "invoices.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv")
                .when().post("/api/invoices/bulk-upload")
                .then().statusCode(200)
                .body("totalRows", equalTo(4))
                .body("successCount", equalTo(2))
                .body("errorCount", equalTo(2))
                .body("status", equalTo("COMPLETED_WITH_ERRORS"))
                .body("errors", hasSize(2))
                .body("errors.lineNo", org.hamcrest.Matchers.containsInAnyOrder(4, 5));

        given().when().get("/api/reports/statement/{code}", "bulk-acme")
                .then().statusCode(200)
                .body("lines", hasSize(2))
                .body("totalAmount", equalTo(1250000.0F));
    }

    @Test
    void upload_allGood_completesClean() {
        seed("bulk-clean", "bulk-clean-type");
        String csv = """
                debtorCode,invoiceTypeCode,issueDate,dueDate,amount,description
                bulk-clean,bulk-clean-type,2026-06-01,2026-07-01,250000,Item A
                """;

        given().multiPart("file", "clean.csv", csv.getBytes(StandardCharsets.UTF_8), "text/csv")
                .when().post("/api/invoices/bulk-upload")
                .then().statusCode(200)
                .body("totalRows", equalTo(1))
                .body("successCount", equalTo(1))
                .body("errorCount", equalTo(0))
                .body("status", equalTo("COMPLETED"));
    }
}
