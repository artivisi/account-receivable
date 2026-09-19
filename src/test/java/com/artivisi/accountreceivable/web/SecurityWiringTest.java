package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

class SecurityWiringTest extends AbstractIntegrationTest {

    @Test
    void adminRequiresAuthentication_redirectsToLogin() {
        given().redirects().follow(false)
                .when().get("/admin")
                .then().statusCode(302)
                .header("Location", containsString("/login"));
    }

    @Test
    void loginPageIsPublic() {
        given().when().get("/login").then().statusCode(200);
    }

    @Test
    void jsonApiStaysOpen() {
        given().when().get("/api/debtors").then().statusCode(200);
    }

    @Test
    void staticCssIsServed() {
        given().when().get("/css/app.css").then().statusCode(200);
    }
}
