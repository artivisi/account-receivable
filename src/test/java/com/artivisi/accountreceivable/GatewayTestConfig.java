package com.artivisi.accountreceivable;

import com.artivisi.accountreceivable.spi.VaKeySupplier;
import com.artivisi.accountreceivable.spi.VaKeys;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-only beans. Which keys a deployment allocates from is its own concern. The
 * notification publisher is faked so tests capture published requests instead of hitting a broker;
 * the outbox-backed EMAIL {@code NotificationSender} comes from production {@code NotificationSenderConfig},
 * and SMS stays unregistered (sms-enabled=false) so a run requesting SMS exercises the fail-loud path.
 */
@TestConfiguration
public class GatewayTestConfig {

    /**
     * One slot per receivable, so every receivable gets its own number and no test collides on one by
     * accident; the tests that exercise number reuse supply a constant slot instead. Without this,
     * every test invoice type would need a VA code mapping before it could be collected.
     */
    @Bean
    public VaKeySupplier vaKeySupplier() {
        return ctx -> new VaKeys(ctx.debtorCode(),
                String.valueOf(Math.abs((long) ctx.consumerReference().hashCode())));
    }

    @Bean
    @Primary
    public CapturingNotificationPublisher capturingNotificationPublisher() {
        return new CapturingNotificationPublisher();
    }
}
