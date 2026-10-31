package com.artivisi.accountreceivable.contract;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter for the scoped command topics ({@link ScopedCommandTopics}). One listener for all of
 * them; the topic a message arrived on decides what it may touch. Absent when no scoped topic is
 * configured, because a listener with no topics is refused by Spring Kafka.
 *
 * <p>{@code autoStartup = "false"} for the same reason as {@link ContractCommandListener}:
 * {@link ContractListenerStarter} starts it, so an unreachable broker cannot stop AR from booting.
 */
@Component
@ConditionalOnExpression("'${ar.contract.mode}' == 'KAFKA' and '${ar.contract.scoped-command-topics}'.trim() != ''")
public class ScopedCommandListener {

    private final ContractCommandHandler handler;

    public ScopedCommandListener(ContractCommandHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "#{@scopedCommandTopics.names()}", autoStartup = "false")
    public void command(String message, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
        handler.handleScoped(topic, message);
    }
}
