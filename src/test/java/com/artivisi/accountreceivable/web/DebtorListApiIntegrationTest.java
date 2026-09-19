package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

/** GET /api/debtors is a paginated, filterable search (debtors can grow large). */
class DebtorListApiIntegrationTest extends AbstractIntegrationTest {

    private void seedDebtor(String code) {
        given().contentType("application/json")
                .body(Map.of("code", code, "name", "Debtor " + code, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
    }

    @Test
    void list_isPaginated_withPageMetadata() {
        for (int i = 0; i < 3; i++) {
            seedDebtor("api-dpage-" + i);
        }

        given().queryParam("q", "api-dpage").queryParam("size", 2).queryParam("page", 0)
                .when().get("/api/debtors")
                .then().statusCode(200)
                .body("content", hasSize(2))
                .body("page.size", equalTo(2))
                .body("page.number", equalTo(0))
                .body("page.totalElements", equalTo(3))
                .body("page.totalPages", equalTo(2));
    }

    @Test
    void list_filtersByQ_onCodeOrName() {
        seedDebtor("api-dfind-x");
        seedDebtor("api-dother-y");

        var codes = given().queryParam("q", "api-dfind")
                .when().get("/api/debtors").then().statusCode(200)
                .extract().jsonPath().getList("content.code", String.class);

        org.assertj.core.api.Assertions.assertThat(codes)
                .contains("api-dfind-x").doesNotContain("api-dother-y");
    }
}
