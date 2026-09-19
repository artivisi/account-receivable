package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.ChargeStatus;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lightweight charge-list row projected directly in SQL (constructor expression) — no entity
 * hydration. A charge targets exactly one of invoice / installment ({@code chk_charge_one_target}),
 * so {@code invoiceId} / {@code invoiceNumber} / {@code debtorName} are coalesced from whichever
 * side is set and are never null. {@code installmentSequence} / {@code installmentCount} are boxed
 * because they come from a left join: null for invoice-target charges.
 */
public record ChargeListItem(
        Instant createdAt,
        String gatewayChargeId,
        String invoiceId,
        String invoiceNumber,
        Integer installmentSequence,
        Integer installmentCount,
        String debtorName,
        String vaNumber,
        BigDecimal amount,
        BigDecimal cumulativePaid,
        ChargeStatus status
) {

    /**
     * Display form of the gateway charge id — production gateways issue UUIDs, so the list shows
     * the first 8 chars (same idiom as the gateway's own admin UI, {@code ViewFormats.shortId})
     * with a copy button carrying the full id.
     */
    public String gatewayChargeIdShort() {
        return gatewayChargeId.length() <= 8 ? gatewayChargeId : gatewayChargeId.substring(0, 8);
    }
}
