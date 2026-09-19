package com.artivisi.accountreceivable.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Penyisihan piutang tak tertagih — an allowance for doubtful accounts built the way PSAK 71 /
 * IFRS 9 permit for trade receivables: a provision matrix of historical loss rates applied to the
 * current aging schedule.
 *
 * <p>Two halves, deliberately separated because they have different natures: {@code rates} is an
 * ASSUMPTION derived from history, {@code allowance} is a FACT (today's outstanding) multiplied by
 * it. Showing them apart lets finance challenge the assumption without re-deriving the arithmetic.
 */
public record ProvisionReportResponse(
        LocalDate asOf,
        int maturityDays,
        List<Rate> rates,
        List<Allowance> allowance,
        BigDecimal totalOutstanding,
        BigDecimal totalAllowance
) {

    /**
     * Of the money still unpaid {@code daysPastDue} days after falling due, how much was ultimately
     * collected — measured over invoices old enough to have had a fair chance.
     */
    public record Rate(
            String typeCode,
            String typeName,
            int daysPastDue,
            BigDecimal reached,
            BigDecimal recovered,
            BigDecimal lossRate,
            long invoices
    ) {
        public BigDecimal recoveryRate() {
            return BigDecimal.ONE.subtract(lossRate);
        }
    }

    /** Today's outstanding in one aging band, and what the matrix says to provide against it. */
    public record Allowance(
            String typeCode,
            String typeName,
            String bucket,
            BigDecimal outstanding,
            BigDecimal lossRate,
            BigDecimal provision,
            long invoices
    ) {
    }
}
