package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** GET /api/invoices is a paginated, filterable search returning lightweight summaries. */
class InvoiceListApiIntegrationTest extends AbstractIntegrationTest {

    private void seedDebtor(String code) {
        given().contentType("application/json")
                .body(Map.of("code", code, "name", "Debtor " + code, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
    }

    private void seedType(String code) {
        given().contentType("application/json")
                .body(Map.of("code", code, "name", "Type " + code, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    private String issue(String debtor, String type, int amount, LocalDate due) {
        return given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(), "dueDate", due.toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201)
                .extract().path("invoiceNumber");
    }

    @Test
    void list_isPaginated_withPageMetadata() {
        seedDebtor("api-page");
        seedType("api-page-t");
        for (int i = 0; i < 3; i++) {
            issue("api-page", "api-page-t", 100_000, LocalDate.now().plusDays(30));
        }

        // A page of size 2 returns 2 rows and stable {content, page:{...}} metadata.
        given().queryParam("q", "api-page").queryParam("size", 2).queryParam("page", 0)
                .when().get("/api/invoices")
                .then().statusCode(200)
                .body("content", hasSize(2))
                .body("page.size", equalTo(2))
                .body("page.number", equalTo(0))
                .body("page.totalElements", equalTo(3))
                .body("page.totalPages", equalTo(2))
                // summary shape: no nested lines/installments, exposes a resolved overdue flag.
                .body("content[0].lines", equalTo(null))
                .body("content[0].overdue", equalTo(false));
    }

    @Test
    void list_filtersByMenunggak_includingInstallmentWithOverdueInstallment() {
        seedDebtor("api-mng");
        seedType("api-mng-t");
        LocalDate today = LocalDate.now();
        // Installment invoice with the first installment already past due -> overdue.
        String overdue = given().contentType("application/json").body(Map.of(
                        "debtorCode", "api-mng", "invoiceTypeCode", "api-mng-t",
                        "issueDate", today.minusDays(40).toString(), "dueDate", today.plusDays(50).toString(),
                        "lines", List.of(Map.of("description", "Paket", "quantity", 1, "unitAmount", 3_000_000)),
                        "installments", List.of(
                                Map.of("dueDate", today.minusDays(10).toString(), "amount", 1_000_000),
                                Map.of("dueDate", today.plusDays(20).toString(), "amount", 1_000_000),
                                Map.of("dueDate", today.plusDays(50).toString(), "amount", 1_000_000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("invoiceNumber");
        String notDue = issue("api-mng", "api-mng-t", 500_000, today.plusDays(30));

        var numbers = given().queryParam("q", "api-mng").queryParam("status", "MENUNGGAK")
                .when().get("/api/invoices").then().statusCode(200)
                .extract().jsonPath().getList("content.invoiceNumber", String.class);

        org.assertj.core.api.Assertions.assertThat(numbers).contains(overdue).doesNotContain(notDue);
    }

    @Test
    void list_rejectsUnknownStatusFilter() {
        given().queryParam("status", "BOGUS").when().get("/api/invoices").then().statusCode(400);
    }
}
