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
import static org.hamcrest.Matchers.startsWith;

class CreditNoteIntegrationTest extends AbstractIntegrationTest {

    private void seed(String debtor, String type) {
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    private String issueSingle(String debtor, String type, int amount) {
        return given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
    }

    @Test
    void creditNote_reducesOutstanding() {
        seed("cn-acme", "cn-type");
        String invoiceId = issueSingle("cn-acme", "cn-type", 1000000);

        String creditNoteId = given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 200000, "reason", "Goodwill discount"))
                .when().post("/api/credit-notes")
                .then().statusCode(201)
                .body("creditNoteNumber", startsWith("CN"))
                .body("amount", equalTo(200000.0F))
                .body("invoiceOutstanding", equalTo(800000.0F))
                .extract().path("id");

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200)
                .body("outstanding", equalTo(800000.0F))
                .body("paymentStatus", equalTo("PARTIALLY_PAID"));

    }

    @Test
    void creditNote_exceedingOutstanding_isRejected() {
        seed("cn-over", "cn-over-type");
        String invoiceId = issueSingle("cn-over", "cn-over-type", 100000);

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 100001))
                .when().post("/api/credit-notes").then().statusCode(400);
    }

    @Test
    void creditNote_onInstallmentInvoice_isRejected() {
        seed("cn-inst", "cn-inst-type");
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", "cn-inst", "invoiceTypeCode", "cn-inst-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(60).toString(),
                        "lines", List.of(Map.of("description", "Course", "quantity", 1, "unitAmount", 1000000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 500000),
                                Map.of("dueDate", LocalDate.now().plusDays(60).toString(), "amount", 500000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        given().contentType("application/json")
                .body(Map.of("invoiceId", invoiceId, "amount", 100000))
                .when().post("/api/credit-notes").then().statusCode(400);
    }
}
