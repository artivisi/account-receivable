package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.repository.ChargeRepository;
import com.artivisi.accountreceivable.repository.ContractEventOutboxRepository;
import com.artivisi.accountreceivable.service.ChargeCancellationDispatcher;
import com.artivisi.accountreceivable.support.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * Cancelling through the REST API — what an operator's repair script or the migration does — is
 * the same operation as the {@code invoice.cancelled} command: the invoice leaves collection, its
 * charge is retired at the gateway, and the upstreams are told.
 */
class InvoiceCancelIntegrationTest extends AbstractIntegrationTest {

    @Autowired ChargeRepository chargeRepository;
    @Autowired ContractEventOutboxRepository outbox;
    @Autowired ChargeCancellationDispatcher dispatcher;

    @Test
    void cancel_retiresTheChargeAndAnnouncesIt_withTheReplacementNamed() {
        ApiClient api = new ApiClient(port, GATEWAY_CLIENT_SECRET);
        api.debtor("cancel-rest", "Debtor", null, null, "ACTIVE");
        api.invoiceType("cancel-rest-type", "Type", true);
        String wrong = api.issueSingle("cancel-rest", "cancel-rest-type", 500_000, LocalDate.now(), LocalDate.now().plusDays(30)).path("id");
        String right = api.issueSingle("cancel-rest", "cancel-rest-type", 400_000, LocalDate.now(), LocalDate.now().plusDays(30)).path("id");
        String rightNumber = given().when().get("/api/invoices/{id}", right).then().extract().path("invoiceNumber");
        api.openCharge(wrong);
        int before = outbox.findByMessageKeyOrderByCreatedAtAsc("cancel-rest").size();

        given().contentType("application/json")
                .body(Map.of("reason", "SUPERSEDED", "replacedBy", rightNumber, "note", "salah nominal"))
                .when().post("/api/invoices/{id}/cancel", wrong)
                .then().statusCode(200).body("paymentStatus", equalTo("CANCELLED"));

        dispatcher.dispatchDue();
        assertThat(chargeRepository.findByInvoiceId(wrong)).allMatch(c -> c.getStatus() == ChargeStatus.CANCELLED);
        List<ContractEventOutbox> events = outbox.findByMessageKeyOrderByCreatedAtAsc("cancel-rest");
        assertThat(events.subList(before, events.size())).extracting(ContractEventOutbox::getEventType)
                .containsExactly("invoice.cancelled");
        assertThat(events.getLast().getPayload()).contains("\"replacedBy\":\"" + rightNumber + "\"")
                .contains("\"decidedBy\":\"api\"");

        // Final: a second cancellation is refused rather than repeated.
        given().contentType("application/json").body(Map.of("reason", "DUPLICATE"))
                .when().post("/api/invoices/{id}/cancel", wrong).then().statusCode(400);
    }
}
