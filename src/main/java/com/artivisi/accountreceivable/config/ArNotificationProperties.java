package com.artivisi.accountreceivable.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.Map;

/**
 * Hub notification config. All required; missing values fail startup (fail loud). Config ids name
 * template sets in the deployment's notification hub — treat each (configId, variable-set) as a
 * versioned contract. The retry/poll fields tune the notification outbox. {@code smsEnabled} gates
 * whether the SMS channel is offered (cost control): when false, {@code mobile} is dropped from
 * issue/payment notifications and no SMS dunning sender is registered.
 */
@Validated
@ConfigurationProperties(prefix = "ar.notification")
public record ArNotificationProperties(
        @NotBlank String topic,
        @NotBlank String issuedConfig,
        @NotBlank String paymentConfig,
        @NotBlank String dunningConfig,
        @Min(1) int maxAttempts,
        @Min(0) long backoffBaseSeconds,
        @Min(1) long pollIntervalMs,
        boolean smsEnabled,
        /**
         * Which names the hub expects. {@code PASSTHROUGH} sends AR's own; {@code TEMPLATE} maps them
         * through {@link #template}. No default: a hub silently refuses a payload whose keys it does
         * not recognise, so this must be a stated choice.
         */
        PayloadMapper payloadMapper,
        /** Required when {@code payloadMapper} is {@code TEMPLATE}; ignored otherwise. */
        Template template
) {

    public enum PayloadMapper {
        /** AR's own variable and envelope names, unchanged. */
        PASSTHROUGH,
        /** Variable and envelope names from {@link Template}. */
        TEMPLATE
    }

    /**
     * How a deployment's hub names things. Each event map is {@code hubVariable → template}; a
     * template is literal text with {@code {variable}} references to AR's own variables for that
     * event (see {@code NotificationPayloadMapper}). A key whose template references a variable that
     * is absent or blank is left out of the message rather than sent empty.
     */
    public record Template(
            Envelope envelope,
            Map<String, String> billIssued,
            Map<String, String> paymentReceived,
            Map<String, String> dunning
    ) {
        public Template {
            if (envelope == null) {
                throw new IllegalArgumentException("ar.notification.template.envelope is required");
            }
            requireEvent("bill-issued", billIssued);
            requireEvent("payment-received", paymentReceived);
            requireEvent("dunning", dunning);
        }

        private static void requireEvent(String name, Map<String, String> templates) {
            if (templates == null || templates.isEmpty()) {
                throw new IllegalArgumentException("ar.notification.template." + name + " is required;"
                        + " a hub template set with no variables is a misconfiguration, not a choice");
            }
            templates.forEach((key, value) -> {
                if (isBlank(value)) {
                    throw new IllegalArgumentException("ar.notification.template." + name + "." + key
                            + " is blank — an unset deployment value, which the hub would refuse");
                }
                // Configuration binding leaves an unresolvable ${...} in place rather than failing, so
                // an unset environment variable would otherwise reach the payer's email verbatim.
                if (value.contains("${")) {
                    throw new IllegalArgumentException("ar.notification.template." + name + "." + key
                            + " has an unresolved placeholder: " + value);
                }
            });
        }
    }

    /** Field names of the message wrapped around the variables. */
    public record Envelope(String configId, String email, String mobile, String data) {
        public Envelope {
            if (isBlank(configId) || isBlank(email) || isBlank(mobile) || isBlank(data)) {
                throw new IllegalArgumentException("ar.notification.template.envelope needs config-id,"
                        + " email, mobile and data — the hub routes on these names");
            }
        }
    }

    public ArNotificationProperties {
        if (payloadMapper == null) {
            throw new IllegalArgumentException(
                    "ar.notification.payload-mapper is required — PASSTHROUGH or TEMPLATE. A hub"
                            + " refuses a payload whose variable names it does not recognise, and"
                            + " says so only in its own log, so this is not safe to default");
        }
        if (payloadMapper == PayloadMapper.TEMPLATE && template == null) {
            throw new IllegalArgumentException(
                    "ar.notification.template is required when payload-mapper is TEMPLATE");
        }
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
