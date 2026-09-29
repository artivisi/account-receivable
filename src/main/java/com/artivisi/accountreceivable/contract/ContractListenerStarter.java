package com.artivisi.accountreceivable.contract;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Starts the contract listener containers, and keeps trying until they start.
 *
 * <p>Exists because an unreachable broker used to take AR down. Spring starts listener containers as
 * part of the context lifecycle, and building a consumer against a bootstrap address that does not
 * resolve throws there — so the whole context refresh was cancelled and systemd restarted the app
 * into the same wall. On 2026-09-29 the host's only nameserver stopped answering and AR was down for
 * 18 minutes; the rollback to the previous build failed for exactly the same reason, which is the
 * part that matters: without this, a deploy during a DNS or broker fault has no way back.
 *
 * <p>The listeners therefore declare {@code autoStartup = "false"} and are started from here instead.
 * AR boots, serves payment webhooks and the admin UI, and attaches to the broker when it can. Nothing
 * outbound is lost meanwhile — events accumulate in the outbox and the dispatcher publishes them
 * once the broker answers. Nothing inbound is lost either: the commands stay on their partitions.
 *
 * <p>It is deliberately not a health indicator. The deploy script gates on {@code /actuator/health},
 * so reporting DOWN while the broker is unreachable would reproduce the failure this class removes —
 * an unhealthy deploy, rolled back into an equally unhealthy previous build.
 */
@Component
@ConditionalOnProperty(prefix = "ar.contract", name = "mode", havingValue = "KAFKA")
public class ContractListenerStarter {

    private static final Logger log = LoggerFactory.getLogger(ContractListenerStarter.class);

    private final KafkaListenerEndpointRegistry registry;
    private final AtomicInteger failures = new AtomicInteger();

    public ContractListenerStarter(KafkaListenerEndpointRegistry registry) {
        this.registry = registry;
    }

    /**
     * Fail loud, but once per outage rather than once per attempt: the first failure is an error,
     * and after that only every tenth, so an hour of broker downtime leaves a readable log instead of
     * a wall of identical lines. Attaching is always reported.
     */
    @Scheduled(fixedDelayString = "${ar.contract.listener-retry-ms:30000}", initialDelay = 0)
    public void startListeners() {
        for (MessageListenerContainer container : registry.getListenerContainers()) {
            if (container.isRunning()) {
                continue;
            }
            try {
                container.start();
                log.info("Contract listener {} attached to the broker", container.getListenerId());
                failures.set(0);
            } catch (Exception e) {
                // A container that threw on the way up may hold a half-built consumer; stop it so the
                // next attempt starts clean rather than leaking one per retry.
                try {
                    container.stop();
                } catch (Exception ignored) {
                    // Nothing to do: the container never came up, and the failure above is the story.
                }
                int count = failures.incrementAndGet();
                if (count == 1 || count % 10 == 0) {
                    log.error("Contract listener {} cannot start (attempt {}): {}. AR is serving, but"
                            + " no v2 command is being consumed.", container.getListenerId(), count, e.toString());
                }
            }
        }
    }
}
