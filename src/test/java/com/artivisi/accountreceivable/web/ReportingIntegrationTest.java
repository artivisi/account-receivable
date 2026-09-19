package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

class ReportingIntegrationTest extends AbstractIntegrationTest {

    private void seed(String debtor, String type) {
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    private String issue(String debtor, String type, int amount) {
        return given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
    }

    @Test
    void statement_listsInvoicesAndTotals() {
        seed("rep-acme", "rep-type");
        String first = issue("rep-acme", "rep-type", 400000);
        issue("rep-acme", "rep-type", 600000);

        given().contentType("application/json").body(Map.of("amount", 100000))
                .when().post("/api/invoices/{id}/payments", first).then().statusCode(200);

        given().when().get("/api/reports/statement/{code}", "rep-acme")
                .then().statusCode(200)
                .body("debtorName", equalTo("Debtor rep-acme"))
                .body("lines", hasSize(2))
                .body("totalAmount", equalTo(1000000.0F))
                .body("totalOutstanding", equalTo(900000.0F));
    }

    @Test
    void statement_unknownDebtor_returns404() {
        given().when().get("/api/reports/statement/{code}", "no-such-debtor")
                .then().statusCode(404);
    }

    @Test
    void recap_groupsByInvoiceType() {
        seed("rec-acme", "rec-type");
        issue("rec-acme", "rec-type", 200000);
        issue("rec-acme", "rec-type", 300000);

        JsonPath json = given().when().get("/api/reports/recap")
                .then().statusCode(200).extract().jsonPath();

        List<Map<String, Object>> mine = json.getList("byType.findAll { it.invoiceTypeCode == 'rec-type' }");
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).get("count")).isEqualTo(2);
        assertThat(((Number) mine.get(0).get("totalAmount")).doubleValue()).isEqualTo(500000.0);
    }
}
