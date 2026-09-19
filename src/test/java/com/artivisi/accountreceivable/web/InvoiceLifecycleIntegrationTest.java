package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;

class InvoiceLifecycleIntegrationTest extends AbstractIntegrationTest {

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

    private Map<String, Object> line(String desc, Object qty, Object unit) {
        return Map.of("description", desc, "quantity", qty, "unitAmount", unit);
    }

    @Test
    void issueSinglePayment_thenPartial_thenFull() {
        seedDebtor("lc-acme");
        seedType("lc-tuition");

        String id = given().contentType("application/json").body(Map.of(
                        "debtorCode", "lc-acme",
                        "invoiceTypeCode", "lc-tuition",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(line("Tuition", 2, 500000))))
                .when().post("/api/invoices")
                .then().statusCode(201)
                .body("invoiceNumber", startsWith("INV"))
                .body("amount", equalTo(1000000.0F))
                .body("outstanding", equalTo(1000000.0F))
                .body("paymentStatus", equalTo("OPEN"))
                .body("installment", equalTo(false))
                .body("overdue", equalTo(false))
                .body("lines", hasSize(1))
                .body("lines[0].lineAmount", equalTo(1000000.0F))
                .extract().path("id");

        given().contentType("application/json").body(Map.of("amount", 400000))
                .when().post("/api/invoices/{id}/payments", id)
                .then().statusCode(200)
                .body("outstanding", equalTo(600000.0F))
                .body("paymentStatus", equalTo("PARTIALLY_PAID"));

        given().contentType("application/json").body(Map.of("amount", 600000))
                .when().post("/api/invoices/{id}/payments", id)
                .then().statusCode(200)
                .body("outstanding", equalTo(0.0F))
                .body("paymentStatus", equalTo("PAID"));
    }

    @Test
    void overpayment_isRejected() {
        seedDebtor("lc-over");
        seedType("lc-over-type");

        String id = given().contentType("application/json").body(Map.of(
                        "debtorCode", "lc-over",
                        "invoiceTypeCode", "lc-over-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(10).toString(),
                        "lines", List.of(line("Item", 1, 100000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        given().contentType("application/json").body(Map.of("amount", 100001))
                .when().post("/api/invoices/{id}/payments", id)
                .then().statusCode(400);
    }

    @Test
    void installmentInvoice_paysDownPerInstallment() {
        seedDebtor("lc-inst");
        seedType("lc-inst-type");

        var response = given().contentType("application/json").body(Map.of(
                        "debtorCode", "lc-inst",
                        "invoiceTypeCode", "lc-inst-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(90).toString(),
                        "lines", List.of(line("Course", 1, 1000000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 300000),
                                Map.of("dueDate", LocalDate.now().plusDays(60).toString(), "amount", 300000),
                                Map.of("dueDate", LocalDate.now().plusDays(90).toString(), "amount", 400000))))
                .when().post("/api/invoices")
                .then().statusCode(201)
                .body("installment", equalTo(true))
                .body("installments", hasSize(3))
                .body("installments[0].outstanding", equalTo(300000.0F))
                .extract();

        String invoiceId = response.path("id");
        String firstInstallmentId = response.path("installments[0].id");

        given().contentType("application/json").body(Map.of("amount", 300000))
                .when().post("/api/installments/{id}/payments", firstInstallmentId)
                .then().statusCode(200)
                .body("outstanding", equalTo(700000.0F))
                .body("paymentStatus", equalTo("PARTIALLY_PAID"))
                .body("installments[0].paymentStatus", equalTo("PAID"))
                .body("installments[0].outstanding", equalTo(0.0F));

        given().when().get("/api/invoices/{id}", invoiceId)
                .then().statusCode(200).body("outstanding", equalTo(700000.0F));
    }

    @Test
    void installmentAmounts_mustSumToInvoiceAmount() {
        seedDebtor("lc-mismatch");
        seedType("lc-mismatch-type");

        given().contentType("application/json").body(Map.of(
                        "debtorCode", "lc-mismatch",
                        "invoiceTypeCode", "lc-mismatch-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(60).toString(),
                        "lines", List.of(line("Course", 1, 1000000)),
                        "installments", List.of(
                                Map.of("dueDate", LocalDate.now().plusDays(30).toString(), "amount", 300000),
                                Map.of("dueDate", LocalDate.now().plusDays(60).toString(), "amount", 300000))))
                .when().post("/api/invoices").then().statusCode(400);
    }

    @Test
    void writeOff_zeroesOutstanding_andBlocksFurtherPayment() {
        seedDebtor("lc-wo");
        seedType("lc-wo-type");

        String id = given().contentType("application/json").body(Map.of(
                        "debtorCode", "lc-wo",
                        "invoiceTypeCode", "lc-wo-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(10).toString(),
                        "lines", List.of(line("Item", 1, 500000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        given().contentType("application/json").body(Map.of("amount", 200000))
                .when().post("/api/invoices/{id}/payments", id).then().statusCode(200);

        given().queryParam("reason", "uncollectible").when().post("/api/invoices/{id}/write-off", id)
                .then().statusCode(200)
                .body("outstanding", equalTo(0.0F))
                .body("paymentStatus", equalTo("WRITTEN_OFF"));

        given().contentType("application/json").body(Map.of("amount", 1000))
                .when().post("/api/invoices/{id}/payments", id).then().statusCode(400);
    }

    @Test
    void unknownDebtorCode_isRejected() {
        seedType("lc-orphan-type");
        given().contentType("application/json").body(Map.of(
                        "debtorCode", "no-such-debtor",
                        "invoiceTypeCode", "lc-orphan-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(10).toString(),
                        "lines", List.of(line("Item", 1, 1000))))
                .when().post("/api/invoices").then().statusCode(400);
    }

    @Test
    void issuedInvoiceIsImmutable_noUpdateEndpoint() {
        seedDebtor("lc-imm");
        seedType("lc-imm-type");
        String id = given().contentType("application/json").body(Map.of(
                        "debtorCode", "lc-imm",
                        "invoiceTypeCode", "lc-imm-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(10).toString(),
                        "lines", List.of(line("Item", 1, 1000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");

        given().contentType("application/json").body(Map.of("amount", 1))
                .when().put("/api/invoices/{id}", id)
                .then().statusCode(405);
    }
}
