package com.artivisi.accountreceivable.config;

import com.artivisi.accountreceivable.repository.InvoiceTypeVaCodeRepository;
import com.artivisi.accountreceivable.service.InvoiceTypeVaKeySupplier;
import com.artivisi.accountreceivable.spi.VaKeySupplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient for the gateway Consumer API and the default supplier of the keys it allocates VA
 * numbers from.
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
    @ConditionalOnMissingBean(VaKeySupplier.class)
    public VaKeySupplier invoiceTypeVaKeySupplier(InvoiceTypeVaCodeRepository vaCodeRepository) {
        return new InvoiceTypeVaKeySupplier(vaCodeRepository);
    }
}
