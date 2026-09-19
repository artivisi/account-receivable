package com.artivisi.accountreceivable.dto;

import java.math.BigDecimal;
import java.util.List;

/** Receivables recap grouped by invoice type. */
public record RecapResponse(
        BigDecimal totalAmount,
        BigDecimal totalOutstanding,
        List<TypeLine> byType
) {

    public record TypeLine(
            String invoiceTypeCode,
            long count,
            BigDecimal totalAmount,
            BigDecimal totalOutstanding
    ) {
    }
}
