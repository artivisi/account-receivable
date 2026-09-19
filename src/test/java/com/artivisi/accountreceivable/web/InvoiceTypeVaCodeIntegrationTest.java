package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

class InvoiceTypeVaCodeIntegrationTest extends AbstractIntegrationTest {

    private String createInvoiceType(String code) {
        return given().contentType("application/json")
                .body(Map.of("code", code, "name", "Type " + code, "active", true))
                .when().post("/api/invoice-types")
                .then().statusCode(201).extract().path("id");
    }

    @Test
    void set_and_get_va_code() {
        String typeId = createInvoiceType("va-spp");

        given().contentType("application/json").body(Map.of("vaCode", "1"))
                .when().put("/api/invoice-types/{id}/va-code", typeId)
                .then().statusCode(200)
                .body("id", notNullValue())
                .body("invoiceTypeCode", equalTo("va-spp"))
                .body("vaCode", equalTo("1"));

        given().when().get("/api/invoice-types/{id}/va-code", typeId)
                .then().statusCode(200)
                .body("vaCode", equalTo("1"));
    }

    @Test
    void update_va_code_is_idempotent() {
        String typeId = createInvoiceType("va-reg");

        given().contentType("application/json").body(Map.of("vaCode", "2"))
                .when().put("/api/invoice-types/{id}/va-code", typeId)
                .then().statusCode(200).body("vaCode", equalTo("2"));

        // Update to a new value — same mapping row, no duplicate.
        given().contentType("application/json").body(Map.of("vaCode", "3"))
                .when().put("/api/invoice-types/{id}/va-code", typeId)
                .then().statusCode(200).body("vaCode", equalTo("3"));

        given().when().get("/api/invoice-types/{id}/va-code", typeId)
                .then().statusCode(200).body("vaCode", equalTo("3"));
    }

    @Test
    void duplicate_va_code_across_types_returns_409() {
        String typeA = createInvoiceType("va-type-a");
        String typeB = createInvoiceType("va-type-b");

        given().contentType("application/json").body(Map.of("vaCode", "5"))
                .when().put("/api/invoice-types/{id}/va-code", typeA)
                .then().statusCode(200);

        given().contentType("application/json").body(Map.of("vaCode", "5"))
                .when().put("/api/invoice-types/{id}/va-code", typeB)
                .then().statusCode(409);
    }

    @Test
    void invalid_va_code_returns_400() {
        String typeId = createInvoiceType("va-bad");

        given().contentType("application/json").body(Map.of("vaCode", "abc"))
                .when().put("/api/invoice-types/{id}/va-code", typeId)
                .then().statusCode(400);

        given().contentType("application/json").body(Map.of("vaCode", "123"))
                .when().put("/api/invoice-types/{id}/va-code", typeId)
                .then().statusCode(400);
    }

    @Test
    void get_missing_mapping_returns_404() {
        String typeId = createInvoiceType("va-no-code");

        given().when().get("/api/invoice-types/{id}/va-code", typeId)
                .then().statusCode(404);
    }

    @Test
    void list_returns_all_mappings() {
        String typeX = createInvoiceType("va-list-x");
        String typeY = createInvoiceType("va-list-y");

        given().contentType("application/json").body(Map.of("vaCode", "7"))
                .when().put("/api/invoice-types/{id}/va-code", typeX).then().statusCode(200);
        given().contentType("application/json").body(Map.of("vaCode", "8"))
                .when().put("/api/invoice-types/{id}/va-code", typeY).then().statusCode(200);

        given().when().get("/api/invoice-type-va-codes")
                .then().statusCode(200)
                .body("findAll { it.invoiceTypeCode == 'va-list-x' }.vaCode[0]", equalTo("7"))
                .body("findAll { it.invoiceTypeCode == 'va-list-y' }.vaCode[0]", equalTo("8"));
    }
}
