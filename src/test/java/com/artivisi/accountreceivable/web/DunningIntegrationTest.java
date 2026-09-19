package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class DunningIntegrationTest extends AbstractIntegrationTest {

    private void seedDebtor(String code, String email) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("code", code);
        body.put("name", "Debtor " + code);
        if (email != null) {
            body.put("email", email);
        }
        body.put("status", "ACTIVE");
        given().contentType("application/json").body(body)
                .when().post("/api/debtors").then().statusCode(201);
    }

    private void seedType(String code) {
        given().contentType("application/json")
                .body(Map.of("code", code, "name", "Type " + code, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
    }

    /** Issues an invoice already {@code daysOverdue} past due. */
    private String issueOverdue(String debtor, String type, int daysOverdue, int amount) {
        return given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().minusDays(daysOverdue + 30L).toString(),
                        "dueDate", LocalDate.now().minusDays(daysOverdue).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("invoiceNumber");
    }

    private static Map<String, Object> reminder(JsonPath json, String invoiceNumber) {
        List<Map<String, Object>> reminders = json.getList("reminders");
        return reminders.stream().filter(r -> invoiceNumber.equals(r.get("invoiceNumber")))
                .findFirst().orElse(null);
    }

    @Test
    void run_sendsToDebtorsWithRecipient_andErrorsWithout() {
        seedDebtor("dun-ok", "ok@example.test");
        seedDebtor("dun-noemail", null);
        seedType("dun-type");
        String withEmail = issueOverdue("dun-ok", "dun-type", 20, 100000);
        String withoutEmail = issueOverdue("dun-noemail", "dun-type", 20, 100000);

        JsonPath json = given().contentType("application/json")
                .body(Map.of("channel", "EMAIL", "minDaysOverdue", 0))
                .when().post("/api/dunning/runs")
                .then().statusCode(201).body("status", org.hamcrest.Matchers.equalTo("DONE"))
                .extract().jsonPath();

        assertThat(reminder(json, withEmail)).containsEntry("status", "SENT");
        Map<String, Object> errored = reminder(json, withoutEmail);
        assertThat(errored).containsEntry("status", "ERROR");
        assertThat((String) errored.get("error")).contains("recipient");
    }

    @Test
    void run_respectsMinDaysOverdue() {
        seedDebtor("dun-recent", "recent@example.test");
        seedType("dun-recent-type");
        String recent = issueOverdue("dun-recent", "dun-recent-type", 5, 100000);

        JsonPath json = given().contentType("application/json")
                .body(Map.of("channel", "EMAIL", "minDaysOverdue", 10))
                .when().post("/api/dunning/runs")
                .then().statusCode(201).extract().jsonPath();

        assertThat(reminder(json, recent)).isNull();
    }

    @Test
    void run_withNoSenderForChannel_isRejected() {
        given().contentType("application/json")
                .body(Map.of("channel", "SMS", "minDaysOverdue", 0))
                .when().post("/api/dunning/runs")
                .then().statusCode(400);
    }
}
