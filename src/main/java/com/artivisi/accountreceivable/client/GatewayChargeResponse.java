package com.artivisi.accountreceivable.client;

import java.util.List;

/**
 * Gateway charge view. Only the fields AR needs are mapped; unknown properties are ignored
 * (Spring Boot's Jackson default).
 *
 * <p>{@code accounts} is where AR learns the VA number: the gateway allocated it, one per escrow.
 */
public record GatewayChargeResponse(
        String id,
        String consumerReference,
        String status,
        List<Account> accounts
) {
    public record Account(String escrowCode, String vaNumber, String interbankVaNumber, String status) {
    }
}
