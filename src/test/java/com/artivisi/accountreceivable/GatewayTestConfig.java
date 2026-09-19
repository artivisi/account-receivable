package com.artivisi.accountreceivable;

import com.artivisi.accountreceivable.spi.VaAllocationContext;
import com.artivisi.accountreceivable.spi.VaNumberSupplier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Test-only beans. The VA-number scheme is a deployment concern the engine does not ship. The
 * notification publisher is faked so tests capture published requests instead of hitting a broker;
 * the outbox-backed EMAIL {@code NotificationSender} comes from production {@code NotificationSenderConfig},
 * and SMS stays unregistered (sms-enabled=false) so a run requesting SMS exercises the fail-loud path.
 */
@TestConfiguration
public class GatewayTestConfig {

    @Bean
    public VaNumberSupplier vaNumberSupplier() {
        return (ctx) -> "VA-" + ctx.escrowCode() + "-" + Integer.toHexString(ctx.consumerReference().hashCode());
    }

    @Bean
    @Primary
    public CapturingNotificationPublisher capturingNotificationPublisher() {
        return new CapturingNotificationPublisher();
    }
}
