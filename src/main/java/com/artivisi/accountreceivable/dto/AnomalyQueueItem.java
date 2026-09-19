package com.artivisi.accountreceivable.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One open receivable finding as the worklist shows it.
 *
 * <p>{@code bookable} and {@code notBookableReason} come from the same check that guards booking, so
 * the screen never offers a button the service would refuse. The reason is set only for a finding
 * whose category could be booked, because for every other category "not bookable" is not news.
 */
public record AnomalyQueueItem(
        String id,
        String invoiceId,
        String invoiceNumber,
        String sourceBillNumber,
        String debtorCode,
        String debtorName,
        String invoiceStatus,
        BigDecimal outstanding,
        String category,
        String source,
        String detail,
        String evidenceRef,
        BigDecimal evidenceAmount,
        Instant evidenceAt,
        String evidenceVaNumber,
        String evidenceBank,
        String raisedBy,
        Instant raisedAt,
        long daysOpen,
        boolean bookable,
        String notBookableReason
) {
}
