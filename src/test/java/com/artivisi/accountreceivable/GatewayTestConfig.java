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

    /**
     * Numeric, like every real one: the published contract types {@code vaNumber} as digits only, so a
     * lettered stub number would let an event that no upstream validator accepts pass our tests.
     */
    @Bean
    public VaNumberSupplier vaNumberSupplier() {
        return (ctx) -> "8990" + String.format("%011d",
                Math.abs((long) (ctx.escrowCode() + ":" + ctx.consumerReference()).hashCode()));
    }

    @Bean
    @Primary
    public CapturingNotificationPublisher capturingNotificationPublisher() {
        return new CapturingNotificationPublisher();
    }
}
