package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The aging report reports totals, not rows, so these assert what a newly issued obligation does to
 * the bucket it belongs in rather than looking itself up in an entry list. Measuring the delta the
 * seeded invoices cause keeps each case independent of whatever else is in the database.
 */
class AgingReportIntegrationTest extends AbstractIntegrationTest {

    private void seed(String debtor, String type) {
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + debtor, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + type, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    private String issue(String debtor, String type, LocalDate due, int amount) {
        return given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor,
                        "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().minusDays(200).toString(),
                        "dueDate", due.toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("invoiceNumber");
    }

    @Test
    void agingPlacesEachObligationInExactlyOneBucket() {
        seed("ag-acme", "ag-type");
        LocalDate today = LocalDate.now();

        Map<String, Long> before = bucketCounts();

        issue("ag-acme", "ag-type", today.plusDays(10), 100000);   // not yet due
        issue("ag-acme", "ag-type", today.minusDays(15), 200000);  // 15 days past due
        issue("ag-acme", "ag-type", today.minusDays(45), 300000);  // 45
        issue("ag-acme", "ag-type", today.minusDays(120), 400000); // 120

        Map<String, Long> after = bucketCounts();

        assertThat(delta(before, after, "CURRENT")).isEqualTo(1);
        assertThat(delta(before, after, "DUE_1_30")).isEqualTo(1);
        assertThat(delta(before, after, "DUE_31_60")).isEqualTo(1);
        assertThat(delta(before, after, "DUE_61_90")).isZero();
        assertThat(delta(before, after, "DUE_90_PLUS")).isEqualTo(1);
    }

    @Test
    void paidAndWrittenOffDropOutOfAging() {
        seed("ag-clean", "ag-clean-type");
        LocalDate today = LocalDate.now();

        Map<String, Long> before = bucketCounts();

        String paid = issue("ag-clean", "ag-clean-type", today.minusDays(5), 100000);
        String writtenOff = issue("ag-clean", "ag-clean-type", today.minusDays(5), 100000);

        given().contentType("application/json").body(Map.of("amount", 100000))
                .when().post("/api/invoices/{id}/payments", idOf(paid)).then().statusCode(200);
        given().queryParam("reason", "uncollectible").when().post("/api/invoices/{id}/write-off", idOf(writtenOff)).then().statusCode(200);

        // Both were DUE_1_30 while open; settling one and forgiving the other must leave no trace.
        Map<String, Long> after = bucketCounts();
        assertThat(delta(before, after, "DUE_1_30")).isZero();
    }

    @Test
    void reportCarriesTotalsAndABoundedOverdueLeaderboard() {
        seed("ag-top", "ag-top-type");
        issue("ag-top", "ag-top-type", LocalDate.now().minusDays(200), 900000);

        JsonPath json = given().when().get("/api/reports/aging")
                .then().statusCode(200).extract().jsonPath();

        assertThat(json.getList("buckets")).hasSize(5);
        assertThat(json.getLong("openDebtorCount")).isPositive();
        // The leaderboard is capped by the endpoint, and never shows anything not yet due.
        assertThat(json.getList("topOverdue")).hasSizeLessThanOrEqualTo(20);
        assertThat(json.getList("topOverdue.bucket", String.class)).doesNotContain("CURRENT");
    }

    private Map<String, Long> bucketCounts() {
        JsonPath json = given().when().get("/api/reports/aging")
                .then().statusCode(200).extract().jsonPath();
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> bucket : json.getList("buckets", Map.class)) {
            counts.put((String) bucket.get("bucket"), ((Number) bucket.get("count")).longValue());
        }
        return counts;
    }

    private static long delta(Map<String, Long> before, Map<String, Long> after, String bucket) {
        return after.getOrDefault(bucket, 0L) - before.getOrDefault(bucket, 0L);
    }

    private String idOf(String invoiceNumber) {
        return given().queryParam("q", invoiceNumber).when().get("/api/invoices")
                .then().statusCode(200).extract().jsonPath()
                .getString("content.find { it.invoiceNumber == '" + invoiceNumber + "' }.id");
    }
}
