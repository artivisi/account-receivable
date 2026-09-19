package com.artivisi.accountreceivable.config;

import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.service.RunningNumberService;
import com.artivisi.accountreceivable.service.numbering.DatedTypeCodeInvoiceNumberStrategy;
import com.artivisi.accountreceivable.service.numbering.InvoiceNumberStrategy;
import com.artivisi.accountreceivable.service.numbering.SequentialInvoiceNumberStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the numbering format for this deployment. Exactly one strategy exists at runtime: a
 * dormant second issuer is still a second issuer, and two of them drawing on one day's sequence
 * produce different debts sharing an identifier, which nothing downstream would catch.
 */
@Configuration
public class InvoiceNumberingConfig {

    @Bean
    public InvoiceNumberStrategy invoiceNumberStrategy(ArInvoiceProperties properties,
                                                       ArGatewayProperties gatewayProperties,
                                                       RunningNumberService runningNumbers,
                                                       InvoiceTypeVaCodeRepository vaCodes) {
        return switch (properties.numberStrategy()) {
            case SEQUENTIAL -> new SequentialInvoiceNumberStrategy(runningNumbers, properties);
            case DATED_TYPE_CODE ->
                    new DatedTypeCodeInvoiceNumberStrategy(runningNumbers, vaCodes, properties,
                            gatewayProperties);
        };
    }
}
