package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.AgingBucket;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Aging snapshot at {@code asOf}. Each open obligation (a single-payment invoice, or one open
 * installment of an installment invoice) is counted in exactly one bucket.
 *
 * <p>The report carries totals, not rows. It used to return one entry per obligation — tens of
 * thousands of them in production, built and serialized so a page could render five numbers. {@code topOverdue}
 * is a bounded leaderboard for the dashboard; to list the obligations behind a bucket, use the paged
 * invoice search, which filters by aging band in SQL.
 */
public record AgingReportResponse(
        LocalDate asOf,
        BigDecimal totalOutstanding,
        List<Bucket> buckets,
        long openDebtorCount,
        List<Entry> topOverdue
) {

    public record Bucket(AgingBucket bucket, long count, BigDecimal amount) {
    }

    public record Entry(
            String invoiceId,
            String invoiceNumber,
            String debtorCode,
            String debtorName,
            Integer installmentSequence,
            LocalDate dueDate,
            long daysOverdue,
            BigDecimal outstanding,
            AgingBucket bucket
    ) {
    }
}
