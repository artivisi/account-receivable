package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.config.ArContractProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

/** When the contract is on, every topic and the producer name must be set. Fail loud, at startup. */
@Component
public class ContractConfigCheck {

    private final ArContractProperties properties;

    public ContractConfigCheck(ArContractProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void check() {
        if (!properties.enabled()) {
            return;
        }
        require("ar.contract.producer-name", properties.producerName());
        require("ar.contract.interbank-va-prefix", properties.interbankVaPrefix());
        ArContractProperties.Topics t = properties.topics();
        if (t == null) {
            throw new IllegalStateException("ar.contract.topics is required unless ar.contract.mode=OFF");
        }
        require("ar.contract.topics.invoice-command", t.invoiceCommand());
        require("ar.contract.topics.debtor-command", t.debtorCommand());
        require("ar.contract.topics.invoice-event", t.invoiceEvent());
        require("ar.contract.topics.payment-event", t.paymentEvent());
    }

    private static void require(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(key + " is required unless ar.contract.mode=OFF");
        }
    }
}
