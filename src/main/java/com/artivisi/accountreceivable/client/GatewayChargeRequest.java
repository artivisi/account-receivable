package com.artivisi.accountreceivable.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Request body for the gateway {@code POST /api/charges} Consumer API.
 *
 * <p>{@code expiresAt} is the receivable's due date as an instant. The gateway enforces expiry at
 * read time and retires the VA number once it passes, so omitting it leaves the number collectible
 * forever — a divergence from every bank adapter, which all derive expiry from a date they were
 * given.
 *
 * <p>No VA number. The gateway allocates one per account, in that escrow's layout, from
 * {@code payerKey} and {@code slotKey}; an account names only its escrow.
 */
public record GatewayChargeRequest(
        String consumerReference,
        String payerName,
        String chargeType,
        BigDecimal amount,
        Instant expiresAt,
        String billNumber,
        String description,
        List<Account> accounts,
        String payerKey,
        String slotKey
) {
    public record Account(String escrowCode) {
    }
}
