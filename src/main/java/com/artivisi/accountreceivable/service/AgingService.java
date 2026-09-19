package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.AgingReportResponse;
import com.artivisi.accountreceivable.entity.AgingBucket;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Aging snapshot, aggregated in the database.
 *
 * <p>An earlier version loaded every open invoice as an entity, walked its installments, and built
 * one {@code Entry} per obligation — tens of thousands of them in production — only for the page to render the
 * five bucket totals and throw the list away. Combined with the same pattern elsewhere it exhausted
 * the heap on 2026-08-18 and took the application down. The report now returns the totals it is
 * actually asked for, plus a bounded overdue leaderboard for the dashboard; per-obligation drill-down
 * belongs to the paged invoice search, which filters by aging band in SQL.
 *
 * <p>An obligation is one open thing to pay: a single-payment invoice, or one open installment of an
 * installment invoice. The bucket boundaries below must stay identical to {@link AgingBucket#of} —
 * that method remains the definition, this SQL is its set-based twin.
 */
@Service
public class AgingService {

    /**
     * Bucketing shared by every query here. Mirrors {@link AgingBucket#of}: not yet due is CURRENT,
     * then 1-30, 31-60, 61-90, and beyond. An installment invoice contributes its open installments
     * rather than itself, so a part-paid schedule ages by the installment actually outstanding.
     */
    private static final String OBLIGATIONS = """
            with obligation as (
                select i.id            as invoice_id,
                       i.id_debtor     as debtor_id,
                       i.outstanding   as amount,
                       i.due_date      as due_date,
                       null::integer   as sequence
                  from invoice i
                 where i.payment_status not in ('WRITTEN_OFF', 'CANCELLED')
                   and i.outstanding > 0
                   and i.is_installment = false
                union all
                select i.id, i.id_debtor, ins.outstanding, ins.due_date, ins.sequence
                  from installment ins
                  join payment_schedule sch on sch.id = ins.id_schedule
                  join invoice i           on i.id = sch.id_invoice
                 where i.payment_status not in ('WRITTEN_OFF', 'CANCELLED')
                   and i.outstanding > 0
                   and ins.payment_status not in ('WRITTEN_OFF', 'CANCELLED')
                   and ins.outstanding > 0
            ), bucketed as (
                select o.*,
                       case when ? <= o.due_date  then 'CURRENT'
                            when ? - o.due_date <= 30 then 'DUE_1_30'
                            when ? - o.due_date <= 60 then 'DUE_31_60'
                            when ? - o.due_date <= 90 then 'DUE_61_90'
                            else 'DUE_90_PLUS' end as bucket,
                       greatest(? - o.due_date, 0) as days_overdue
                  from obligation o
            )
            """;

    private static final String BUCKET_TOTALS_SQL = OBLIGATIONS + """
            select bucket, count(*) as obligations, sum(amount) as outstanding
              from bucketed
             group by bucket
            """;

    private static final String OPEN_DEBTORS_SQL = OBLIGATIONS + """
            select count(distinct debtor_id) as debtors from bucketed
            """;

    private static final String TOP_OVERDUE_SQL = OBLIGATIONS + """
            select b.invoice_id, i.invoice_number, d.code as debtor_code, d.name as debtor_name,
                   b.sequence, b.due_date, b.days_overdue, b.amount, b.bucket
              from bucketed b
              join invoice i on i.id = b.invoice_id
              join debtor  d on d.id = b.debtor_id
             where b.bucket <> 'CURRENT'
             order by b.amount desc, i.invoice_number
             limit ?
            """;

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public AgingService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AgingReportResponse report(int topOverdueLimit) {
        LocalDate asOf = LocalDate.now(clock);

        Map<AgingBucket, AgingReportResponse.Bucket> found = new EnumMap<>(AgingBucket.class);
        BigDecimal total = BigDecimal.ZERO;
        for (Map<String, Object> row : jdbc.queryForList(BUCKET_TOTALS_SQL, bucketArgs(asOf))) {
            AgingBucket bucket = AgingBucket.valueOf((String) row.get("bucket"));
            BigDecimal outstanding = money(row.get("outstanding"));
            found.put(bucket, new AgingReportResponse.Bucket(
                    bucket, ((Number) row.get("obligations")).longValue(), outstanding));
            total = total.add(outstanding);
        }

        // Every bucket is reported, including the empty ones — a missing row would read as "no data"
        // where the truth is "nothing is this overdue".
        List<AgingReportResponse.Bucket> buckets = new ArrayList<>();
        for (AgingBucket bucket : AgingBucket.values()) {
            buckets.add(found.getOrDefault(bucket,
                    new AgingReportResponse.Bucket(bucket, 0L, BigDecimal.ZERO)));
        }

        long openDebtorCount = jdbc.queryForObject(OPEN_DEBTORS_SQL, Long.class, bucketArgs(asOf));

        Object[] topArgs = new Object[]{asOf, asOf, asOf, asOf, asOf, topOverdueLimit};
        List<AgingReportResponse.Entry> topOverdue = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(TOP_OVERDUE_SQL, topArgs)) {
            Number sequence = (Number) row.get("sequence");
            topOverdue.add(new AgingReportResponse.Entry(
                    (String) row.get("invoice_id"),
                    (String) row.get("invoice_number"),
                    (String) row.get("debtor_code"),
                    (String) row.get("debtor_name"),
                    sequence == null ? null : sequence.intValue(),
                    ((java.sql.Date) row.get("due_date")).toLocalDate(),
                    ((Number) row.get("days_overdue")).longValue(),
                    money(row.get("amount")),
                    AgingBucket.valueOf((String) row.get("bucket"))));
        }

        return new AgingReportResponse(asOf, total, buckets, openDebtorCount, topOverdue);
    }

    /** The five positional {@code asOf} bindings the bucketing CASE and days_overdue need. */
    private static Object[] bucketArgs(LocalDate asOf) {
        return new Object[]{asOf, asOf, asOf, asOf, asOf};
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : ((BigDecimal) value).setScale(2, RoundingMode.HALF_UP);
    }
}
