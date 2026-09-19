package com.artivisi.accountreceivable.contract;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka adapter over {@link ContractCommandHandler}; one listener per command topic. */
@Component
@ConditionalOnProperty(prefix = "ar.contract", name = "mode", havingValue = "KAFKA")
public class ContractCommandListener {

    private final ContractCommandHandler handler;

    public ContractCommandListener(ContractCommandHandler handler) {
        this.handler = handler;
    }

    @KafkaListener(topics = "${ar.contract.topics.invoice-command}")
    public void invoiceCommand(String message) {
        handler.handle("invoice-command", message);
    }

    @KafkaListener(topics = "${ar.contract.topics.debtor-command}")
    public void debtorCommand(String message) {
        handler.handle("debtor-command", message);
    }
}
