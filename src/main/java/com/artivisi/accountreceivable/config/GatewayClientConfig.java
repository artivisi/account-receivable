package com.artivisi.accountreceivable.config;

import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.service.EncodedVaNumberSupplier;
import com.artivisi.accountreceivable.spi.VaNumberSupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the gateway Consumer API and default VA number supplier.
 */
@Configuration
public class GatewayClientConfig {

    @Bean
    public RestClient gatewayRestClient(ArGatewayProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Client-Id", properties.clientId())
                .defaultHeader("X-Client-Secret", properties.clientSecret())
                .build();
    }

    @Bean
    @ConditionalOnMissingBean(VaNumberSupplier.class)
    public VaNumberSupplier encodedVaNumberSupplier(ArGatewayProperties properties,
                                                     InvoiceTypeVaCodeRepository vaCodeRepository) {
        if (properties.vaPrefix() == null) {
            throw new IllegalStateException("ar.gateway.va-prefix is required (no custom VaNumberSupplier provided)");
        }
        if (properties.vaDigitLength() == null) {
            throw new IllegalStateException("ar.gateway.va-digit-length is required (no custom VaNumberSupplier provided)");
        }
        if (properties.vaInvoiceTypeDigits() == null) {
            throw new IllegalStateException("ar.gateway.va-invoice-type-digits is required (no custom VaNumberSupplier provided)");
        }
        return new EncodedVaNumberSupplier(properties, vaCodeRepository);
    }
}
