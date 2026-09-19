package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.ReceivableAnomaly;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import com.artivisi.accountreceivable.repository.ReceivableAnomalyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The anomaly table holds findings AR could not have reached on its own, so the two properties worth
 * testing are the ones that decide whether a queue built on it stays trustworthy: a daily check must
 * be able to re-report without stacking duplicates, and a resolution must say who decided and what.
 */
class ReceivableAnomalyIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired ReceivableAnomalyRepository anomalies;
    @Autowired InvoiceRepository invoices;

    private Invoice seedInvoice() {
        int n = SEQ.incrementAndGet();
        String debtor = "anom-debtor-" + n;
        String type = "anom-type-" + n;
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + n, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + n, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
        String id = given().contentType("application/json").body(Map.of(
                        "debtorCode", debtor, "invoiceTypeCode", type,
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Tuition", "quantity", 1,
                                "unitAmount", 500_000))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id");
        return invoices.findById(id).orElseThrow();
    }

    private ReceivableAnomaly finding(Invoice invoice, String evidenceRef) {
        ReceivableAnomaly a = new ReceivableAnomaly();
        a.setInvoice(invoice);
        a.setCategory("NOTIFIED_NOT_SETTLED");
        a.setSource("RECON");
        a.setDetail("Bank notified the payment but no credit appears on the settlement account");
        a.setEvidenceRef(evidenceRef);
        a.setRaisedBy("recon-run");
        return a;
    }

    @Test
    void reReportingTheSameFindingDoesNotStackDuplicates() {
        Invoice invoice = seedInvoice();
        ReceivableAnomaly first = anomalies.saveAndFlush(finding(invoice, "FT26189RHRNF"));

        // A check that runs every night re-reports what is still unresolved. Landing a second row
        // would both double-count the queue and reset how long the finding has been open, which is
        // the reviewer's main signal about it.
        assertThatThrownBy(() -> anomalies.saveAndFlush(finding(invoice, "FT26189RHRNF")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(anomalies.findByInvoiceIdAndCategoryAndEvidenceRef(
                invoice.getId(), "NOTIFIED_NOT_SETTLED", "FT26189RHRNF"))
                .get().extracting(ReceivableAnomaly::getId).isEqualTo(first.getId());
        assertThat(first.getCreatedAt()).isNotNull();
        assertThat(first.open()).isTrue();
    }

    @Test
    void aDifferentEvidenceReferenceIsADifferentFinding() {
        Invoice invoice = seedInvoice();
        anomalies.saveAndFlush(finding(invoice, "FT26189RHRNF"));
        anomalies.saveAndFlush(finding(invoice, "FT26209PV99W"));

        assertThat(anomalies.findByInvoiceIdOrderByCreatedAtDesc(invoice.getId())).hasSize(2);
    }

    @Test
    void aResolutionMustCarryWhoDecidedAndWhat() {
        Invoice invoice = seedInvoice();
        ReceivableAnomaly a = anomalies.saveAndFlush(finding(invoice, "FT26189RHRNF"));

        // Half-filled resolutions are how a queue stops being trustworthy: the row leaves the list
        // and nobody can say who took it out or on what grounds.
        a.setResolvedAt(Instant.now());
        assertThatThrownBy(() -> anomalies.saveAndFlush(a))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void resolvingRemovesItFromTheOpenList() {
        Invoice invoice = seedInvoice();
        ReceivableAnomaly a = anomalies.saveAndFlush(finding(invoice, "FT26189RHRNF"));
        assertThat(anomalies.findByResolvedAtIsNullOrderByCreatedAtDesc())
                .extracting(ReceivableAnomaly::getId).contains(a.getId());

        a.setResolvedAt(Instant.now());
        a.setResolvedBy("finance");
        a.setResolution("CONFIRMED_WITH_BANK");
        a.setResolutionNote("BSI confirmed the credit landed in the next batch");
        anomalies.saveAndFlush(a);

        assertThat(anomalies.findByResolvedAtIsNullOrderByCreatedAtDesc())
                .extracting(ReceivableAnomaly::getId).doesNotContain(a.getId());
    }
}
