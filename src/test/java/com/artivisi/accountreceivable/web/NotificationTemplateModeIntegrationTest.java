package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.CapturingNotificationPublisher;
import com.artivisi.accountreceivable.dto.NotificationRequest;
import com.artivisi.accountreceivable.repository.NotificationOutboxRepository;
import com.artivisi.accountreceivable.service.NotificationDispatcher;
import com.artivisi.accountreceivable.service.notification.NotificationPayloadMapper;
import com.artivisi.accountreceivable.service.notification.TemplateNotificationPayloadMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The application booted the way a TEMPLATE deployment runs it: the template imported through
 * {@code spring.config.import}, its {@code ${...}} deployment values resolved from the environment,
 * and a real bill going through the outbox to the publisher.
 */
@TestPropertySource(properties = {
        "spring.config.import=classpath:notification/template-example.yml",
        "ar.notification.payload-mapper=TEMPLATE",
        "AR_NOTIFICATION_BANK_NAME=Bank Contoh",
        "AR_NOTIFICATION_VA_DISPLAY_PREFIX=8888001",
        "AR_NOTIFICATION_CONTACT_INFO=021-0000000",
        "AR_NOTIFICATION_CONTACT_INFO_FULL=<ul><li>Layanan Keuangan : 021-0000000</li></ul>"
})
class NotificationTemplateModeIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private NotificationPayloadMapper payloadMapper;
    @Autowired
    private NotificationDispatcher dispatcher;
    @Autowired
    private CapturingNotificationPublisher publisher;
    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @BeforeEach
    void reset() {
        publisher.reset();
        outboxRepository.deleteAll();
    }

    @Test
    void billIssuedReachesThePublisherInTheHubsNames() {
        assertThat(payloadMapper).isInstanceOf(TemplateNotificationPayloadMapper.class);

        given().contentType("application/json").body(Map.of(
                        "code", "ntf-tpl", "name", "Budi Santoso", "email", "tpl@example.test", "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", "ntf-tpl-type", "name", "Biaya Pendidikan", "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
        String invoiceId = given().contentType("application/json").body(Map.of(
                        "debtorCode", "ntf-tpl", "invoiceTypeCode", "ntf-tpl-type",
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Item", "quantity", 1, "unitAmount", 250000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
        given().when().post("/api/invoices/{id}/charge", invoiceId).then().statusCode(201);

        dispatcher.dispatchDue();

        assertThat(publisher.published()).hasSize(1);
        NotificationRequest sent = publisher.published().getFirst();
        assertThat(sent.data())
                .containsEntry("nama", "Budi Santoso")
                .containsEntry("jumlah", "250000.00")
                .containsEntry("keterangan", "Biaya Pendidikan")
                .containsEntry("contactinfoFull", "<ul><li>Layanan Keuangan : 021-0000000</li></ul>")
                .doesNotContainKeys("debtorName", "amount");
        assertThat(sent.data().get("rekening")).startsWith("Bank Contoh 8888001");
        assertThat(payloadMapper.envelope(sent).keySet())
                .containsExactly("konfigurasi", "email", "mobile", "data");
    }
}
