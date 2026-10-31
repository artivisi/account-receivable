package com.artivisi.accountreceivable;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Boots the full app on a random port against a real PostgreSQL 18 container, with a stub server
 * for the payment-gateway Consumer API. Container and stub are
 * JVM-wide singletons; datasource, secrets, and base URLs are injected so production placeholders
 * never resolve in tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Test properties rather than dynamic ones, so a subclass can override them with its own. A
// @DynamicPropertySource in the base class wins over a subclass's @TestPropertySource, which makes
// anything registered there impossible for one test to vary.
@TestPropertySource(properties = {
        "ar.notification.payload-mapper=PASSTHROUGH",
        // Contract events are recorded to the outbox and never published: tests read the outbox.
        "ar.contract.mode=OUTBOX_ONLY",
})
@Import(GatewayTestConfig.class)
public abstract class AbstractIntegrationTest {

    protected static final String GATEWAY_CLIENT_SECRET = "test-gateway-secret";
    protected static final String GATEWAY_ESCROW_CODE = "test-escrow";

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:18"));

    static final HttpServer GATEWAY_STUB;
    private static final AtomicLong CHARGE_SEQ = new AtomicLong();

    // Gateway charge-cancel stub controls.
    private static final AtomicInteger GW_CANCEL_COUNT = new AtomicInteger();
    private static volatile boolean gatewayCancelFail = false;

    // Gateway charge-reprice stub: how a plan's amount-due reaches the payer.
    private static final AtomicInteger GW_REPRICE_COUNT = new AtomicInteger();
    /** Last amount the gateway stub saw on a reprice request. */
    protected static volatile String lastRepriceAmount;
    private static final java.util.regex.Pattern AMOUNT_PATTERN =
            java.util.regex.Pattern.compile("\"amount\"\\s*:\\s*\"?([0-9.]+)\"?");

    protected static int gatewayRepriceCount() {
        return GW_REPRICE_COUNT.get();
    }

    // Gateway charge-extend stub: how a corrected due date reaches the payer.
    private static final AtomicInteger GW_EXTEND_COUNT = new AtomicInteger();
    /** Last expiresAt the gateway stub saw on an extend request. */
    protected static volatile String lastExtendExpiresAt;

    protected static int gatewayExtendCount() {
        return GW_EXTEND_COUNT.get();
    }

    // Stateful charge stub: enforces the gateway's one-active-VA invariant so tests can exercise
    // VA-number reuse (a new bill superseding the active charge on that number).
    private static final java.util.Set<String> STUB_ACTIVE_VA = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Map<String, String> STUB_CHARGE_VA = new java.util.concurrent.ConcurrentHashMap<>();
    // The real gateway is idempotent on (consumer, consumerReference) and answers a repeat with the
    // charge it already has, WHATEVER its status — including a cancelled one. Modelling that is what
    // makes a spent reference visible to tests; without it the stub minted a fresh live charge for a
    // reference the gateway would have refused to reuse.
    private static final java.util.Map<String, String> STUB_CHARGE_BY_REF = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, String> STUB_CHARGE_STATUS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.regex.Pattern REF_PATTERN =
            java.util.regex.Pattern.compile("\"consumerReference\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern VA_PATTERN =
            java.util.regex.Pattern.compile("\"vaNumber\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern PAYER_KEY_PATTERN =
            java.util.regex.Pattern.compile("\"payerKey\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern SLOT_KEY_PATTERN =
            java.util.regex.Pattern.compile("\"slotKey\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern ESCROW_PATTERN =
            java.util.regex.Pattern.compile("\"escrowCode\"\\s*:\\s*\"([^\"]+)\"");
    /** What the stub prefixes to a number to form the interbank one, standing in for the escrow's. */
    protected static final String STUB_INTERBANK_PREFIX = "8888001";
    // The number each charge was allocated, kept after its VA is freed: an idempotent repeat of a
    // cancelled charge still reports the number it had, as the real gateway does.
    private static final java.util.Map<String, String> STUB_CHARGE_NUMBER = new java.util.concurrent.ConcurrentHashMap<>();
    // Payer keys the stub refuses as KEY_UNENCODABLE, standing in for an escrow layout too narrow for them.
    private static final java.util.Set<String> STUB_UNENCODABLE_PAYER_KEYS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.regex.Pattern BILL_PATTERN =
            java.util.regex.Pattern.compile("\"billNumber\"\\s*:\\s*\"([^\"]+)\"");
    private static final java.util.regex.Pattern EXPIRES_PATTERN =
            java.util.regex.Pattern.compile("\"expiresAt\"\\s*:\\s*\"([^\"]+)\"");
    /** Last billNumber the gateway stub saw on a create-charge request (for parity assertions). */
    protected static volatile String lastChargeBillNumber;
    /** Last expiresAt the gateway stub saw on a create-charge request (for parity assertions). */
    protected static volatile String lastChargeExpiresAt;

    static {
        POSTGRES.start();
        GATEWAY_STUB = newServer();
        GATEWAY_STUB.createContext("/api/charges", exchange -> {
            String body = readBody(exchange.getRequestBody());
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/cancel")) {
                if (gatewayCancelFail) {
                    respond(exchange, 500, "{\"error\":\"stub cancel failure\"}");
                    return;
                }
                String id = path.substring("/api/charges/".length(), path.length() - "/cancel".length());
                String freed = STUB_CHARGE_VA.remove(id);
                if (freed != null) {
                    STUB_ACTIVE_VA.remove(freed);
                }
                STUB_CHARGE_STATUS.put(id, "CANCELLED");
                GW_CANCEL_COUNT.incrementAndGet();
                respond(exchange, 200, "{}");
                return;
            }
            if (path.endsWith("/reprice")) {
                java.util.regex.Matcher am = AMOUNT_PATTERN.matcher(body);
                lastRepriceAmount = am.find() ? am.group(1) : null;
                GW_REPRICE_COUNT.incrementAndGet();
                respond(exchange, 200, "{}");
                return;
            }
            if (path.endsWith("/extend")) {
                java.util.regex.Matcher xm = EXPIRES_PATTERN.matcher(body);
                lastExtendExpiresAt = xm.find() ? xm.group(1) : null;
                GW_EXTEND_COUNT.incrementAndGet();
                respond(exchange, 200, "{}");
                return;
            }
            java.util.regex.Matcher rm = REF_PATTERN.matcher(body);
            String reference = rm.find() ? rm.group(1) : null;
            String known = reference == null ? null : STUB_CHARGE_BY_REF.get(reference);
            String escrow = firstMatch(ESCROW_PATTERN, body);
            if (known != null) {
                // Idempotent hit: the charge this reference already names, in whatever state it is.
                respond(exchange, 201, chargeJson(known, STUB_CHARGE_STATUS.getOrDefault(known, "ACTIVE"),
                        escrow, STUB_CHARGE_NUMBER.get(known)));
                return;
            }
            // The gateway allocates the number; a consumer that still sends one, or omits the keys,
            // is on the old protocol and the real gateway refuses it.
            String payerKey = firstMatch(PAYER_KEY_PATTERN, body);
            String slotKey = firstMatch(SLOT_KEY_PATTERN, body);
            if (extractVaNumber(body) != null || payerKey == null || slotKey == null || escrow == null) {
                respond(exchange, 400, "{\"status\":400,\"detail\":\"stub: expected payerKey, slotKey and"
                        + " accounts[].escrowCode with no vaNumber\"}");
                return;
            }
            if (STUB_UNENCODABLE_PAYER_KEYS.contains(payerKey)) {
                respond(exchange, 400, "{\"status\":400,\"title\":\"Key unencodable\",\"code\":\"KEY_UNENCODABLE\","
                        + "\"field\":\"payerKey\",\"escrowCode\":\"" + escrow + "\",\"detail\":\"payerKey '"
                        + payerKey + "' does not fit escrow " + escrow + "\"}");
                return;
            }
            String vaNumber = allocateStubNumber(payerKey, slotKey);
            java.util.regex.Matcher bm = BILL_PATTERN.matcher(body);
            lastChargeBillNumber = bm.find() ? bm.group(1) : null;
            java.util.regex.Matcher em = EXPIRES_PATTERN.matcher(body);
            lastChargeExpiresAt = em.find() ? em.group(1) : null;
            if (!STUB_ACTIVE_VA.add(vaNumber)) {
                respond(exchange, 409, "{\"status\":409,\"title\":\"VA number busy\",\"code\":\"VA_NUMBER_BUSY\","
                        + "\"escrowCode\":\"" + escrow + "\",\"vaNumber\":\"" + vaNumber + "\","
                        + "\"heldByConsumerReference\":\"" + holderOf(vaNumber) + "\","
                        + "\"detail\":\"vaNumber already active for escrow " + escrow + ": " + vaNumber + "\"}");
                return;
            }
            String id = "gw-" + CHARGE_SEQ.incrementAndGet();
            STUB_CHARGE_VA.put(id, vaNumber);
            STUB_CHARGE_NUMBER.put(id, vaNumber);
            if (reference != null) {
                STUB_CHARGE_BY_REF.put(reference, id);
            }
            STUB_CHARGE_STATUS.put(id, "ACTIVE");
            respond(exchange, 201, chargeJson(id, "ACTIVE", escrow, vaNumber));
        });
        GATEWAY_STUB.start();
    }

    private static String extractVaNumber(String body) {
        java.util.regex.Matcher m = VA_PATTERN.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    private static String firstMatch(java.util.regex.Pattern pattern, String body) {
        java.util.regex.Matcher m = pattern.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Deterministic in the keys, as the real allocator is: the same payer and slot always get the
     * same number. Numeric, like every real one: the published contract types {@code vaNumber} as
     * digits only, so a lettered stub number would let an event no upstream validator accepts pass.
     */
    private static String allocateStubNumber(String payerKey, String slotKey) {
        return "8990" + String.format("%011d", Math.abs((long) (payerKey + ":" + slotKey).hashCode()));
    }

    /** The reference of the charge holding {@code vaNumber}, which the real 409 names. */
    private static String holderOf(String vaNumber) {
        String holderId = STUB_CHARGE_VA.entrySet().stream()
                .filter(e -> e.getValue().equals(vaNumber)).map(java.util.Map.Entry::getKey)
                .findFirst().orElseThrow(() -> new IllegalStateException("stub: no charge holds " + vaNumber));
        return STUB_CHARGE_BY_REF.entrySet().stream()
                .filter(e -> e.getValue().equals(holderId)).map(java.util.Map.Entry::getKey)
                .findFirst().orElseThrow(() -> new IllegalStateException("stub: charge " + holderId + " has no reference"));
    }

    private static String chargeJson(String id, String status, String escrow, String vaNumber) {
        return "{\"id\":\"" + id + "\",\"status\":\"" + status + "\",\"accounts\":[{\"escrowCode\":\"" + escrow
                + "\",\"vaNumber\":\"" + vaNumber + "\",\"interbankVaNumber\":\"" + STUB_INTERBANK_PREFIX + vaNumber
                + "\",\"status\":\"" + status + "\"}]}";
    }

    /** Make the stub refuse this payer key as one the escrow's layout cannot hold. */
    protected static void refuseGatewayPayerKey(String payerKey) {
        STUB_UNENCODABLE_PAYER_KEYS.add(payerKey);
    }

    @LocalServerPort
    protected int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("ar.security.secret-key",
                () -> Base64.getEncoder().encodeToString(new byte[32]));
        registry.add("ar.admin.username", () -> "admin");
        registry.add("ar.admin.password", () -> "admin-secret");
        registry.add("ar.invoice.number-prefix", () -> "INV");
        registry.add("ar.invoice.credit-note-prefix", () -> "CN");
        registry.add("ar.invoice.number-pad-length", () -> "6");
        registry.add("ar.invoice.number-strategy", () -> "SEQUENTIAL");
        registry.add("ar.gateway.base-url",
                () -> "http://127.0.0.1:" + GATEWAY_STUB.getAddress().getPort());
        registry.add("ar.gateway.client-id", () -> "ar-test");
        registry.add("ar.gateway.client-secret", () -> GATEWAY_CLIENT_SECRET);
        registry.add("ar.gateway.escrow-code", () -> GATEWAY_ESCROW_CODE);
        registry.add("ar.gateway.max-attempts", () -> "3");
        registry.add("ar.gateway.backoff-base-seconds", () -> "0");
        registry.add("ar.gateway.poll-interval-ms", () -> "3600000");
        // Kafka producer is never actually used in tests (the capturing publisher stands in), but
        // the auto-configured KafkaTemplate needs a bootstrap-servers value to construct.
        registry.add("spring.kafka.producer.bootstrap-servers", () -> "127.0.0.1:9092");
        registry.add("ar.notification.topic", () -> "notification-test");
        registry.add("ar.notification.issued-config", () -> "invoice-issued");
        registry.add("ar.notification.payment-config", () -> "payment-received");
        registry.add("ar.notification.dunning-config", () -> "invoice-overdue");
        registry.add("ar.notification.max-attempts", () -> "3");
        registry.add("ar.notification.backoff-base-seconds", () -> "0");
        registry.add("ar.notification.poll-interval-ms", () -> "3600000");
        registry.add("ar.notification.sms-enabled", () -> "false");
        registry.add("ar.contract.producer-name", () -> "account-receivable-test");
        registry.add("ar.contract.topics.invoice-command", () -> "invoice-command-test");
        registry.add("ar.contract.topics.debtor-command", () -> "debtor-command-test");
        registry.add("ar.contract.topics.invoice-event", () -> "invoice-event-test");
        registry.add("ar.contract.topics.payment-event", () -> "payment-event-test");
        registry.add("ar.contract.max-installments", () -> "12");
        registry.add("ar.contract.scoped-command-topics", () -> "scoped-command-test=41");
        registry.add("ar.contract.max-attempts", () -> "3");
        registry.add("ar.contract.backoff-base-seconds", () -> "0");
        registry.add("ar.contract.poll-interval-ms", () -> "3600000");
    }

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
        // Clear VA-occupancy state so charge stubs don't leak the one-active-VA invariant across tests.
        STUB_ACTIVE_VA.clear();
        STUB_CHARGE_VA.clear();
        STUB_CHARGE_BY_REF.clear();
        STUB_CHARGE_STATUS.clear();
        STUB_CHARGE_NUMBER.clear();
        STUB_UNENCODABLE_PAYER_KEYS.clear();
    }

    /**
     * Settle a charge at the stub by the reference it was opened under: mark it PAID and free its VA
     * number, as the real gateway does when a CLOSED charge is paid. A test that posts a PAID webhook
     * for a plan leg must call this first, because AR then reopens on the same number and the stub
     * would otherwise still hold it.
     */
    public static void settleGatewayCharge(String consumerReference) {
        String id = STUB_CHARGE_BY_REF.get(consumerReference);
        if (id == null) {
            throw new IllegalStateException("stub has no charge for reference " + consumerReference);
        }
        STUB_CHARGE_STATUS.put(id, "PAID");
        String freed = STUB_CHARGE_VA.remove(id);
        if (freed != null) {
            STUB_ACTIVE_VA.remove(freed);
        }
    }

    protected static void resetGatewayCancelStub() {
        GW_CANCEL_COUNT.set(0);
        gatewayCancelFail = false;
    }

    protected static void setGatewayCancelFail(boolean fail) {
        gatewayCancelFail = fail;
    }

    protected static int gatewayCancelCount() {
        return GW_CANCEL_COUNT.get();
    }

    /**
     * Free a VA number in the charge stub, standing in for the gateway retiring the VA of a fully
     * paid charge. The stub deliberately has no payment concept — payments reach AR as webhooks, not
     * through the Consumer API — so a test that pays a bill and then expects its number to be
     * reusable has to say so.
     */
    protected static void releaseGatewayVa(String vaNumber) {
        STUB_ACTIVE_VA.remove(vaNumber);
    }

    /**
     * Cancel a charge at the stub by the reference it was opened under, as the operator's
     * orphan-cleanup does. The reference stays known and now names a cancelled charge — the state
     * that made eight repairs adopt dead charges on 2026-08-25.
     */
    protected static void cancelGatewayChargeByReference(String consumerReference) {
        String id = STUB_CHARGE_BY_REF.get(consumerReference);
        if (id == null) {
            throw new IllegalStateException("stub has no charge for reference " + consumerReference);
        }
        STUB_CHARGE_STATUS.put(id, "CANCELLED");
        String freed = STUB_CHARGE_VA.remove(id);
        if (freed != null) {
            STUB_ACTIVE_VA.remove(freed);
        }
    }

    /** Mark the charge under this reference PAID at the stub, keeping its VA occupied. */
    protected static void payGatewayChargeByReference(String consumerReference) {
        String id = STUB_CHARGE_BY_REF.get(consumerReference);
        if (id == null) {
            throw new IllegalStateException("stub has no charge for reference " + consumerReference);
        }
        STUB_CHARGE_STATUS.put(id, "PAID");
    }

    /** What the stub believes the charge under this reference is, or null if it never saw it. */
    protected static String gatewayStatusOfReference(String consumerReference) {
        String id = STUB_CHARGE_BY_REF.get(consumerReference);
        return id == null ? null : STUB_CHARGE_STATUS.get(id);
    }

    private static HttpServer newServer() {
        try {
            return HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start stub server", e);
        }
    }

    private static String readBody(InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
