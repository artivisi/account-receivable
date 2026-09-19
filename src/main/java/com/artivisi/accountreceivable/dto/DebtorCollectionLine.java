package com.artivisi.accountreceivable.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One applied allocation against a debtor, projected straight from SQL — the debtor ledger needs
 * these five values and nothing else, so no {@code CashApplication} or {@code CashApplicationLine}
 * entity is ever hydrated.
 *
 * <p>{@code owningDueDate} is the deadline of whatever the line settled: the installment's own due
 * date when it targets an installment, the invoice's otherwise. {@code installmentSequence} is null
 * for a single-payment invoice.
 */
public record DebtorCollectionLine(
        Instant receivedAt,
        String gatewayPaymentReference,
        BigDecimal allocatedAmount,
        Integer installmentSequence,
        LocalDate owningDueDate
) {
}
