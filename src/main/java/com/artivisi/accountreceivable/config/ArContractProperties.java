package com.artivisi.accountreceivable.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The v2 async contract: commands in from upstream applications, events out. Mode OFF leaves the
 * listener and the outbox dispatcher unregistered and records no events, so a deployment with no
 * upstream on a broker runs the REST API alone. Otherwise every topic and the producer name are
 * required — checked at startup by {@code ContractConfigCheck}, fail loud.
 */
@Validated
@ConfigurationProperties(prefix = "ar.contract")
public record ArContractProperties(
        /** OFF: nothing recorded. OUTBOX_ONLY: events recorded, no broker (tests, dry runs). KAFKA: live. */
        Mode mode,
        /** The {@code producer} stamped on every event. */
        String producerName,
        Topics topics,
        /** Upper bound on legs in a paymentPlan; policy, not a technical limit. */
        @Min(1) int maxInstallments,
        @Min(1) int maxAttempts,
        @Min(0) long backoffBaseSeconds,
        @Min(1) long pollIntervalMs,
        /** Prefix that turns the bank's VA number into the interbank one; deployment-specific. */
        String interbankVaPrefix
) {
    public record Topics(String invoiceCommand, String debtorCommand, String invoiceEvent, String paymentEvent) {
    }

    public enum Mode { OFF, OUTBOX_ONLY, KAFKA }

    public boolean enabled() {
        return mode != null && mode != Mode.OFF;
    }
}
