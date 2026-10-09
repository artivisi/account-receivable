package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import com.artivisi.accountreceivable.dto.AnomalyQueueItem;
import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.PaymentSource;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.entity.Invoice;
import com.artivisi.accountreceivable.entity.PaymentStatus;
import com.artivisi.accountreceivable.entity.ReceivableAnomaly;
import com.artivisi.accountreceivable.exception.InvalidRequestException;
import com.artivisi.accountreceivable.repository.CashApplicationRepository;
import com.artivisi.accountreceivable.repository.ContractEventOutboxRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import com.artivisi.accountreceivable.repository.ReceivableAnomalyRepository;
import com.artivisi.accountreceivable.support.ApiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Booking a payment the bank received and no book recorded. The properties that matter are the ones
 * that keep a manual money path honest: it books exactly what the bank recorded, it tells the campus
 * apps and nobody else, it refuses before writing anything, and it can never book the same bank
 * reference twice.
 */
class ReceivableAnomalyBookingIntegrationTest extends AbstractIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Instant BANK_TIME = Instant.parse("2026-07-16T15:16:32Z");
    private static final String BANK_VA = "812345678901";

    @Autowired ReceivableAnomalyService service;
    @Autowired ReceivableAnomalyRepository anomalies;
    @Autowired InvoiceRepository invoices;
    @Autowired CashApplicationRepository cashApplications;
    @Autowired ContractEventOutboxRepository outbox;
    @Autowired JdbcTemplate jdbc;

    private record Seeded(String debtor, String invoiceId) {
    }

    private Seeded seedDebtorAndType(String prefix, java.util.function.Function<String[], String> issue) {
        int n = SEQ.incrementAndGet();
        String debtor = prefix + "-debtor-" + n;
        String type = prefix + "-type-" + n;
        given().contentType("application/json")
                .body(Map.of("code", debtor, "name", "Debtor " + n, "status", "ACTIVE"))
                .when().post("/api/debtors").then().statusCode(201);
        given().contentType("application/json")
                .body(Map.of("code", type, "name", "Type " + n, "active", true))
                .when().post("/api/invoice-types").then().statusCode(201);
        return new Seeded(debtor, issue.apply(new String[]{debtor, type}));
    }

    private Seeded seedInvoice(int amount) {
        return seedDebtorAndType("book", dt -> given().contentType("application/json").body(Map.of(
                        "debtorCode", dt[0], "invoiceTypeCode", dt[1],
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", LocalDate.now().plusDays(30).toString(),
                        "lines", List.of(Map.of("description", "Tuition", "quantity", 1, "unitAmount", amount))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id"));
    }

    private Seeded seedPlan(int firstLeg, int secondLeg) {
        LocalDate first = LocalDate.now().plusDays(10);
        LocalDate second = LocalDate.now().plusDays(40);
        return seedDebtorAndType("plan", dt -> given().contentType("application/json").body(Map.of(
                        "debtorCode", dt[0], "invoiceTypeCode", dt[1],
                        "issueDate", LocalDate.now().toString(),
                        "dueDate", second.toString(),
                        "lines", List.of(Map.of("description", "Tuition", "quantity", 1,
                                "unitAmount", firstLeg + secondLeg)),
                        "installments", List.of(
                                Map.of("dueDate", first.toString(), "amount", firstLeg),
                                Map.of("dueDate", second.toString(), "amount", secondLeg))))
                .when().post("/api/invoices").then().statusCode(201).extract().path("id"));
    }

    private ReceivableAnomaly finding(String invoiceId, String category, String ref, BigDecimal amount) {
        ReceivableAnomaly a = new ReceivableAnomaly();
        a.setInvoice(invoices.findById(invoiceId).orElseThrow());
        a.setCategory(category);
        a.setSource("RECON");
        a.setDetail("Bank menerima pembayaran yang tidak pernah dibukukan");
        a.setEvidenceRef(ref);
        a.setEvidenceAmount(amount);
        a.setEvidenceAt(BANK_TIME);
        a.setEvidenceVaNumber(BANK_VA);
        a.setEvidenceBank("bsi");
        a.setRaisedBy("recon-test");
        return anomalies.saveAndFlush(a);
    }

    private ReceivableAnomaly bankPaid(String invoiceId, String ref, BigDecimal amount) {
        return finding(invoiceId, ReceivableAnomalyService.BANK_PAID_NOT_BOOKED, ref, amount);
    }

    private static String ref() {
        return "BANKREF" + System.nanoTime();
    }

    private long notificationRows() {
        Long n = jdbc.queryForObject("select count(*) from notification_outbox", Long.class);
        return n == null ? 0 : n;
    }

    private void assertNothingBooked(String ref, String anomalyId, String invoiceId, String outstanding) {
        assertThat(cashApplications.findBySourceAndPaymentReference(PaymentSource.GATEWAY, ref)).isEmpty();
        assertThat(anomalies.findById(anomalyId).orElseThrow().open()).isTrue();
        assertThat(invoices.findById(invoiceId).orElseThrow().getOutstanding())
                .isEqualByComparingTo(outstanding);
    }

    @Test
    void booksWhatTheBankRecorded_tellsTheCampusApps_andSendsNoReceipt() {
        Seeded s = seedInvoice(500_000);
        String ref = ref();
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref, new BigDecimal("500000.00"));
        long receiptsBefore = notificationRows();
        int eventsBefore = outbox.findByMessageKeyOrderByCreatedAtAsc(s.debtor()).size();

        service.bookPayment(a.getId(), "Dicocokkan dengan laporan portal bank", "finance-1");

        CashApplication booked = cashApplications.findBySourceAndPaymentReference(PaymentSource.GATEWAY, ref).orElseThrow();
        assertThat(booked.getStatus()).isEqualTo(CashApplicationStatus.APPLIED);
        assertThat(booked.getAmount()).isEqualByComparingTo("500000");
        assertThat(booked.getReceivedAt()).isEqualTo(BANK_TIME);
        Long lines = jdbc.queryForObject(
                "select count(*) from cash_application_line where id_cash_application = ? and id_invoice = ?",
                Long.class, booked.getId(), s.invoiceId());
        assertThat(lines).isEqualTo(1L);

        Invoice invoice = invoices.findById(s.invoiceId()).orElseThrow();
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
        assertThat(invoice.getOutstanding()).isEqualByComparingTo("0");

        ReceivableAnomaly closed = anomalies.findById(a.getId()).orElseThrow();
        assertThat(closed.open()).isFalse();
        assertThat(closed.getResolution()).isEqualTo(ReceivableAnomalyService.PAYMENT_BOOKED);
        assertThat(closed.getResolvedBy()).isEqualTo("finance-1");
        assertThat(closed.getResolutionNote()).isEqualTo("Dicocokkan dengan laporan portal bank");

        List<ContractEventOutbox> events = outbox.findByMessageKeyOrderByCreatedAtAsc(s.debtor());
        assertThat(events.subList(eventsBefore, events.size())).extracting(ContractEventOutbox::getEventType)
                .containsExactly("payment.received");
        assertThat(events.getLast().getPayload())
                .contains("\"vaNumber\":\"" + BANK_VA + "\"")
                .contains("\"bank\":\"bsi\"")
                .contains("\"reference\":\"" + ref + "\"")
                .contains("\"amount\":\"500000.00\"")
                .contains("\"outstanding\":\"0.00\"")
                .contains("\"invoiceStatus\":\"PAID\"");

        // A receipt for a payment made weeks ago would read as a new charge.
        assertThat(notificationRows()).isEqualTo(receiptsBefore);
    }

    @Test
    void partPaysASingleInvoice() {
        Seeded s = seedInvoice(500_000);
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref(), new BigDecimal("200000"));

        service.bookPayment(a.getId(), "Dicocokkan", "finance-1");

        Invoice invoice = invoices.findById(s.invoiceId()).orElseThrow();
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(invoice.getOutstanding()).isEqualByComparingTo("300000");
    }

    @Test
    void aPlanTakesTheBookedPaymentOnItsEarliestLegs() {
        Seeded s = seedPlan(300_000, 200_000);
        String ref = ref();
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref, new BigDecimal("400000"));

        service.bookPayment(a.getId(), "Dicocokkan", "finance-1");

        CashApplication booked = cashApplications.findBySourceAndPaymentReference(PaymentSource.GATEWAY, ref).orElseThrow();
        List<Map<String, Object>> legs = jdbc.queryForList("""
                select i.sequence, i.outstanding, i.payment_status, l.allocated_amount
                  from installment i
                  join payment_schedule s on s.id = i.id_schedule
                  left join cash_application_line l on l.id_installment = i.id and l.id_cash_application = ?
                 where s.id_invoice = ? order by i.sequence""", booked.getId(), s.invoiceId());
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).get("payment_status")).isEqualTo("PAID");
        assertThat((BigDecimal) legs.get(0).get("allocated_amount")).isEqualByComparingTo("300000");
        assertThat(legs.get(1).get("payment_status")).isEqualTo("PARTIALLY_PAID");
        assertThat((BigDecimal) legs.get(1).get("outstanding")).isEqualByComparingTo("100000");
        assertThat((BigDecimal) legs.get(1).get("allocated_amount")).isEqualByComparingTo("100000");
        Invoice invoice = invoices.findById(s.invoiceId()).orElseThrow();
        assertThat(invoice.getOutstanding()).isEqualByComparingTo("100000");
        assertThat(invoice.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
    }

    @Test
    void refusesAFindingThatIsNotAnUnbookedBankPayment() {
        Seeded s = seedInvoice(500_000);
        String ref = ref();
        ReceivableAnomaly a = finding(s.invoiceId(), "NOTIFIED_NOT_SETTLED", ref, new BigDecimal("500000"));

        assertThatThrownBy(() -> service.bookPayment(a.getId(), "Dicocokkan", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("BANK_PAID_NOT_BOOKED");
        assertNothingBooked(ref, a.getId(), s.invoiceId(), "500000");
    }

    @Test
    void refusesAFindingWithoutTheBanksFiguresAndSaysWhichAreMissing() {
        Seeded s = seedInvoice(500_000);
        String ref = ref();
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref, null);
        a.setEvidenceVaNumber(null);
        anomalies.saveAndFlush(a);

        assertThatThrownBy(() -> service.bookPayment(a.getId(), "Dicocokkan", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("amount")
                .hasMessageContaining("VA number");
        assertNothingBooked(ref, a.getId(), s.invoiceId(), "500000");

        AnomalyQueueItem row = service.openForInvoice(s.invoiceId()).getFirst();
        assertThat(row.bookable()).isFalse();
        assertThat(row.notBookableReason()).contains("amount");
    }

    @Test
    void refusesABankReferenceAlreadyBooked_namingTheInvoiceThatHoldsIt() {
        Seeded first = seedInvoice(500_000);
        Seeded second = seedInvoice(500_000);
        String ref = ref();
        ReceivableAnomaly bookedOnce = bankPaid(first.invoiceId(), ref, new BigDecimal("500000"));
        ReceivableAnomaly sameMoney = bankPaid(second.invoiceId(), ref, new BigDecimal("500000"));
        service.bookPayment(bookedOnce.getId(), "Dicocokkan", "finance-1");
        String firstNumber = invoices.findById(first.invoiceId()).orElseThrow().getInvoiceNumber();

        assertThatThrownBy(() -> service.bookPayment(sameMoney.getId(), "Dicocokkan", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("already booked")
                .hasMessageContaining(firstNumber);
        assertThat(anomalies.findById(sameMoney.getId()).orElseThrow().open()).isTrue();
        assertThat(invoices.findById(second.invoiceId()).orElseThrow().getOutstanding())
                .isEqualByComparingTo("500000");
    }

    @Test
    void refusesMoreThanTheOutstanding_neverParkingTheExcess() {
        Seeded s = seedInvoice(500_000);
        String ref = ref();
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref, new BigDecimal("600000"));

        assertThatThrownBy(() -> service.bookPayment(a.getId(), "Dicocokkan", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("exceeds");
        assertNothingBooked(ref, a.getId(), s.invoiceId(), "500000");
    }

    @Test
    void refusesAReceivableThatIsNoLongerOpen() {
        Seeded s = seedInvoice(500_000);
        String ref = ref();
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref, new BigDecimal("500000"));
        given().queryParam("reason", "test fixture")
                .post("/api/invoices/{id}/write-off", s.invoiceId()).then().statusCode(200);

        assertThatThrownBy(() -> service.bookPayment(a.getId(), "Dicocokkan", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("WRITTEN_OFF");
        assertThat(cashApplications.findBySourceAndPaymentReference(PaymentSource.GATEWAY, ref)).isEmpty();
        assertThat(anomalies.findById(a.getId()).orElseThrow().open()).isTrue();
    }

    @Test
    void refusesWhileAVaIsStillCollectingTheReceivable() {
        Seeded s = seedInvoice(500_000);
        new ApiClient(port, GATEWAY_CLIENT_SECRET).openCharge(s.invoiceId()).then().statusCode(201);
        String ref = ref();
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref, new BigDecimal("500000"));

        // Booking would mark the debt settled while the VA still asks for Rp 500.000.
        assertThatThrownBy(() -> service.bookPayment(a.getId(), "Dicocokkan", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("live VA");
        assertNothingBooked(ref, a.getId(), s.invoiceId(), "500000");
        assertThat(service.openForInvoice(s.invoiceId()).getFirst().bookable()).isFalse();
    }

    @Test
    void refusesWithoutANote_andRefusesAFindingAlreadyDecided() {
        Seeded s = seedInvoice(500_000);
        String ref = ref();
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref, new BigDecimal("500000"));

        assertThatThrownBy(() -> service.bookPayment(a.getId(), "  ", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("note");
        assertNothingBooked(ref, a.getId(), s.invoiceId(), "500000");

        service.resolve(a.getId(), "REFUNDED", "Dana sudah dikembalikan bank", "finance-2");
        assertThatThrownBy(() -> service.bookPayment(a.getId(), "Dicocokkan", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("already decided");
        assertThat(cashApplications.findBySourceAndPaymentReference(PaymentSource.GATEWAY, ref)).isEmpty();
    }

    @Test
    void resolvingRecordsWhoDecidedWhatAndWhy_andReservesPaymentBookedForBooking() {
        Seeded s = seedInvoice(500_000);
        ReceivableAnomaly a = bankPaid(s.invoiceId(), ref(), new BigDecimal("500000"));

        assertThatThrownBy(() -> service.resolve(a.getId(), ReceivableAnomalyService.PAYMENT_BOOKED, "x", "finance-1"))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("only by booking");
        assertThatThrownBy(() -> service.resolve(a.getId(), "MADE_UP", "x", "finance-1"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> service.resolve(a.getId(), "ALREADY_RECORDED", null, "finance-1"))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(service.openForInvoice(s.invoiceId())).extracting(AnomalyQueueItem::id).contains(a.getId());

        service.resolve(a.getId(), "ALREADY_RECORDED", "Tercatat dengan referensi FT", "finance-1");

        ReceivableAnomaly closed = anomalies.findById(a.getId()).orElseThrow();
        assertThat(closed.getResolution()).isEqualTo("ALREADY_RECORDED");
        assertThat(closed.getResolvedBy()).isEqualTo("finance-1");
        assertThat(closed.getResolvedAt()).isNotNull();
        assertThat(service.openForInvoice(s.invoiceId())).isEmpty();
        assertThat(invoices.findById(s.invoiceId()).orElseThrow().getOutstanding()).isEqualByComparingTo("500000");
    }
}
