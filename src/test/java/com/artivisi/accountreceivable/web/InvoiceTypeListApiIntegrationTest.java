package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** GET /api/invoice-types is a paginated, filterable search. */
class InvoiceTypeListApiIntegrationTest extends AbstractIntegrationTest {

    private void seedType(String code, boolean active) {
        given().contentType("application/json")
                .body(Map.of("code", code, "name", "Type " + code, "active", active))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    @Test
    void list_isPaginated_withPageMetadata_includingInactive() {
        seedType("api-tpage-a", true);
        seedType("api-tpage-b", true);
        seedType("api-tpage-c", false); // API lists all types, active or not.

        given().queryParam("q", "api-tpage").queryParam("size", 2).queryParam("page", 0)
                .when().get("/api/invoice-types")
                .then().statusCode(200)
                .body("content", hasSize(2))
                .body("page.size", equalTo(2))
                .body("page.totalElements", equalTo(3))
                .body("page.totalPages", equalTo(2));
    }

    @Test
    void list_filtersByQ_onCodeOrName() {
        seedType("api-tfind", true);
        seedType("api-tother", true);

        var codes = given().queryParam("q", "api-tfind")
                .when().get("/api/invoice-types").then().statusCode(200)
                .extract().jsonPath().getList("content.code", String.class);

        org.assertj.core.api.Assertions.assertThat(codes)
                .contains("api-tfind").doesNotContain("api-tother");
    }
}
