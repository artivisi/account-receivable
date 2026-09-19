package com.artivisi.accountreceivable.client;

/**
 * Gateway charge view. Only the fields AR needs are mapped; unknown properties are ignored
 * (Spring Boot's Jackson default).
 */
public record GatewayChargeResponse(
        String id,
        String consumerReference,
        String status
) {
}
