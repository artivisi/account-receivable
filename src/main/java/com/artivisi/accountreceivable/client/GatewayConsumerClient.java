package com.artivisi.accountreceivable.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Thin client over the gateway Consumer API. Auth headers are applied by the configured RestClient. */
@Component
public class GatewayConsumerClient {

    private final RestClient gatewayRestClient;

    public GatewayConsumerClient(RestClient gatewayRestClient) {
        this.gatewayRestClient = gatewayRestClient;
    }

    public GatewayChargeResponse openCharge(GatewayChargeRequest request) {
        return gatewayRestClient.post()
                .uri("/api/charges")
                .body(request)
                .retrieve()
                .body(GatewayChargeResponse.class);
    }

    public void cancelCharge(String gatewayChargeId) {
        gatewayRestClient.post()
                .uri("/api/charges/{id}/cancel", gatewayChargeId)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Move a charge's deadline. The gateway also restores a VA its expiry sweep already retired, so
     * this is what makes a corrected due date reach the payer instead of stopping at our books.
     */
    public void extendCharge(String gatewayChargeId, java.time.Instant expiresAt) {
        gatewayRestClient.post()
                .uri("/api/charges/{id}/extend", gatewayChargeId)
                .body(new ExtendChargeRequest(expiresAt))
                .retrieve()
                .toBodilessEntity();
    }

    /** Change what the charge answers on inquiry; the VA number and deadline stay. */
    public void repriceCharge(String gatewayChargeId, java.math.BigDecimal amount) {
        gatewayRestClient.post()
                .uri("/api/charges/{id}/reprice", gatewayChargeId)
                .body(new RepriceChargeRequest(amount))
                .retrieve()
                .toBodilessEntity();
    }

    /** Request body for {@code POST /api/charges/{id}/reprice}. */
    public record RepriceChargeRequest(java.math.BigDecimal amount) {
    }

    /** Request body for {@code POST /api/charges/{id}/extend}. */
    public record ExtendChargeRequest(java.time.Instant expiresAt) {
    }
}
