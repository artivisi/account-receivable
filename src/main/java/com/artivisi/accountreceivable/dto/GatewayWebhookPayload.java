package com.artivisi.accountreceivable.dto;

import java.math.BigDecimal;

/** Inbound gateway payment webhook body. Unknown fields ignored (Spring Boot Jackson default). */
public record GatewayWebhookPayload(
        String eventType,
        String chargeId,
        String consumerReference,
        String chargeStatus,
        BigDecimal paymentAmount,
        String bankReference
) {
}
