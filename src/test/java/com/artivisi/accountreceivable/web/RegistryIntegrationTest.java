package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

class RegistryIntegrationTest extends AbstractIntegrationTest {

    private static Map<String, Object> debtorRequest(String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Acme Corp");
        body.put("email", "billing@acme.example");
        body.put("phone", "+62811000111");
        body.put("status", "ACTIVE");
        return body;
    }

    private static Map<String, Object> invoiceTypeRequest(String code) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Tuition Fee");
        body.put("active", true);
        return body;
    }

    @Test
    void createAndReadDebtor() {
        String id = given().contentType("application/json").body(debtorRequest("acme"))
                .when().post("/api/debtors")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("code", equalTo("acme"))
                .body("status", equalTo("ACTIVE"))
                .extract().path("id");

        given().when().get("/api/debtors/{id}", id)
                .then().statusCode(200)
                .body("code", equalTo("acme"))
                .body("name", equalTo("Acme Corp"));
    }

    @Test
    void duplicateDebtorCode_returns409() {
        given().contentType("application/json").body(debtorRequest("dup-debtor"))
                .when().post("/api/debtors").then().statusCode(201);

        given().contentType("application/json").body(debtorRequest("dup-debtor"))
                .when().post("/api/debtors").then().statusCode(409);
    }

    @Test
    void invalidDebtor_returns400() {
        Map<String, Object> body = debtorRequest("bad-debtor");
        body.remove("name");
        given().contentType("application/json").body(body)
                .when().post("/api/debtors").then().statusCode(400);
    }

    @Test
    void unknownDebtor_returns404() {
        given().when().get("/api/debtors/{id}", "does-not-exist")
                .then().statusCode(404);
    }

    @Test
    void createAndReadInvoiceType() {
        String id = given().contentType("application/json").body(invoiceTypeRequest("tuition"))
                .when().post("/api/invoice-types")
                .then().statusCode(201)
                .body("id", notNullValue())
                .body("code", equalTo("tuition"))
                .body("active", equalTo(true))
                .extract().path("id");

        given().when().get("/api/invoice-types/{id}", id)
                .then().statusCode(200)
                .body("name", equalTo("Tuition Fee"));
    }

    @Test
    void duplicateInvoiceTypeCode_returns409() {
        given().contentType("application/json").body(invoiceTypeRequest("dup-type"))
                .when().post("/api/invoice-types").then().statusCode(201);

        given().contentType("application/json").body(invoiceTypeRequest("dup-type"))
                .when().post("/api/invoice-types").then().statusCode(409);
    }

    @Test
    void invalidInvoiceType_missingName_returns400() {
        Map<String, Object> body = invoiceTypeRequest("bad-type");
        body.remove("name");
        given().contentType("application/json").body(body)
                .when().post("/api/invoice-types").then().statusCode(400);
    }
}
