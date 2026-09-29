package com.artivisi.accountreceivable.contract;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka adapter over {@link ContractCommandHandler}; one listener per command topic.
 *
 * <p>{@code autoStartup = "false"}: {@link ContractListenerStarter} owns starting these, so a broker
 * AR cannot reach at boot no longer stops AR from booting.
 */
@Component
@ConditionalOnProperty(prefix = "ar.contract", name = "mode", havingValue = "KAFKA")
public class ContractCommandListener {

    private final ContractCommandHandler handler;

    public ContractCommandListener(ContractCommandHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "${ar.contract.topics.invoice-command}", autoStartup = "false")
    public void invoiceCommand(String message) {
        handler.handle("invoice-command", message);
    }

    @KafkaListener(topics = "${ar.contract.topics.debtor-command}", autoStartup = "false")
    public void debtorCommand(String message) {
        handler.handle("debtor-command", message);
    }
}
