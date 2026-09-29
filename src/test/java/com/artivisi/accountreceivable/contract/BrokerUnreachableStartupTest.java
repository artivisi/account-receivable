package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.AbstractIntegrationTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/**
 * AR must boot with the contract switched on and the broker unreachable.
 *
 * <p>This is the 2026-09-29 outage as a test. The host's only nameserver stopped answering, the
 * consumer could not resolve the bootstrap address, the listener container failed to start, and the
 * context refresh was cancelled — so AR crash-looped for 18 minutes, and the rollback to the previous
 * build failed identically. A broker fault must cost the v2 command stream, not the application:
 * payment webhooks, cash application and the admin UI all keep working without Kafka.
 *
 * <p>{@code broker.invalid} is an unresolvable name by RFC 6761, so this reproduces the DNS failure
 * itself rather than a refused connection — a refused connection was never fatal, an unresolvable
 * address was.
 */
@TestPropertySource(properties = {
        "ar.contract.mode=KAFKA",
        "spring.kafka.consumer.bootstrap-servers=broker.invalid:9092",
        "spring.kafka.consumer.group-id=ar-broker-unreachable-test",
        // Do not let the retry fire during the test: one attempt at startup is what is under test.
        "ar.contract.listener-retry-ms=3600000",
})
class BrokerUnreachableStartupTest extends AbstractIntegrationTest {

    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired(required = false) ContractListenerStarter starter;

    @Test
    void theApplicationStartsAndServesEvenThoughNoListenerCouldAttach() {
        // If the context had failed the way it did in production, this test could not run at all.
        assertThat(starter).as("the starter is registered when the contract runs on Kafka").isNotNull();
        assertThat(registry.getListenerContainers()).as("the listeners are registered").isNotEmpty();

        // Driven from this thread rather than read after the scheduler's own run: a container is
        // briefly marked running between start() throwing and the cleanup stop(), so asserting on
        // whatever state the scheduler happened to leave behind is a race.
        starter.startListeners();

        // stop() on a concurrent container is asynchronous — isRunning() stays true while a child
        // consumer winds down — so this waits for the cleanup instead of sampling it. The property
        // that matters is that a failed start leaves nothing behind, not how fast it gets there.
        assertThat(settles(() -> registry.getListenerContainers().stream()
                .noneMatch(MessageListenerContainer::isRunning)))
                .as("nothing attached, and nothing was left half-started either")
                .isTrue();

        RestAssured.given().when().get("/actuator/health").then()
                .statusCode(200).body("status", equalTo("UP"));
    }

    /** True once the condition holds, or false if it has not within five seconds. */
    private static boolean settles(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    @Test
    void theRestOfARKeepsWorking() {
        // The half that must survive a broker outage: money in, recorded, answerable.
        RestAssured.given().when().get("/api/invoices?page=0&size=1").then().statusCode(200);
    }
}
