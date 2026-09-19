package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.ProvisionReportResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a provision matrix from the receivable ledger's own history and applies it to today's
 * aging — the PSAK 71 / IFRS 9 simplified approach for trade receivables.
 *
 * <p>Two rules keep the rates honest:
 * <ul>
 *   <li><b>Only matured invoices count.</b> A bill issued last month has not had time to be paid;
 *       including it would count "not yet paid" as "never paid" and inflate every loss rate.</li>
 *   <li><b>Cancelled bills are excluded.</b> A withdrawn bill was never a credit loss — it was
 *       usually reissued under another number. Counting them as losses would make the matrix
 *       meaningless.</li>
 * </ul>
 *
 * <p>The rate answers a question finance can argue with: of the money still unpaid N days after
 * falling due, what share was ultimately collected? Money-weighted, because a provision is about
 * rupiah rather than invoice counts.
 */
@Service
public class ProvisionService {

    /**
     * The point at which each aging band is ENTERED, in days past due. {@code -1} is the band every
     * invoice passes through — not yet due — and its population is therefore all invoices, giving a
     * lifetime loss rate rather than a past-due one.
     *
     * <p>That distinction is the whole matrix here. In this ledger due dates are generous and
     * debtors pay early or not at all: of the matured invoices ever paid, well over 99% were settled
     * on or before the due date. So "still unpaid once due" is very nearly
     * "never paid", and every past-due band earns a loss rate close to 100% — which is honest, but
     * applying it to not-yet-due receivables would provide against the entire book.
     */
    private static final int[] THRESHOLDS = {-1, 0, 30, 60, 90};
    /** An invoice younger than this has not had a fair chance to be paid, so it cannot inform a rate. */
    private static final int MATURITY_DAYS = 365;

    private static final String RATE_SQL = """
            with settled as (
              select c.id_invoice,
                     max(ca.received_at) as last_paid,
                     sum(l.allocated_amount) as paid
                from cash_application ca
                join cash_application_line l on l.id_cash_application = ca.id
                join charge c on c.id = ca.id_charge
               where ca.status in ('APPLIED', 'PARTIALLY_APPLIED')
               group by 1
            ),
            mature as (
              select i.id, it.code as type_code, it.name as type_name, i.amount, i.due_date,
                     coalesce(s.paid, 0) as paid,
                     case when s.last_paid is null then null
                          else (s.last_paid at time zone 'Asia/Jakarta')::date - i.due_date end as days_to_settle
                from invoice i
                join invoice_type it on it.id = i.id_invoice_type
                left join settled s on s.id_invoice = i.id
               where i.issue_date <= current_date - make_interval(days => ?)
                 and i.payment_status <> 'CANCELLED'
            )
            select m.type_code, m.type_name, t.threshold,
                   count(*) as invoices,
                   sum(m.amount - m.paid_before) as reached,
                   sum(m.paid_after) as recovered
              from (
                select m.*, 0::numeric as paid_before, m.paid as paid_after from mature m
              ) m
              -- thresholds are a compile-time constant, so they are inlined rather than bound:
              -- passing an Integer[] through JdbcTemplate relies on driver array conversion
              cross join (values (-1), (0), (30), (60), (90)) as t(threshold)
             -- threshold -1 = the whole population (every invoice is at some point not yet due)
             where t.threshold < 0 or m.days_to_settle is null or m.days_to_settle > t.threshold
             group by 1, 2, 3
             order by 1, 3
            """;

    private static final String ALLOWANCE_SQL = """
            select it.code, it.name,
                   case when current_date <= i.due_date then 'CURRENT'
                        when current_date - i.due_date <= 30 then 'DUE_1_30'
                        when current_date - i.due_date <= 60 then 'DUE_31_60'
                        when current_date - i.due_date <= 90 then 'DUE_61_90'
                        else 'DUE_90_PLUS' end as bucket,
                   count(*) as invoices,
                   sum(i.outstanding) as outstanding
              from invoice i
              join invoice_type it on it.id = i.id_invoice_type
             where i.payment_status in ('OPEN', 'PARTIALLY_PAID')
               and i.outstanding > 0
             group by 1, 2, 3
             order by 1, 3
            """;

    /** Aging band -> the days-past-due threshold whose historical rate applies to it. */
    private static final Map<String, Integer> BUCKET_THRESHOLD = Map.of(
            "CURRENT", -1, "DUE_1_30", 0, "DUE_31_60", 30, "DUE_61_90", 60, "DUE_90_PLUS", 90);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    public ProvisionService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProvisionReportResponse report() {
        List<ProvisionReportResponse.Rate> rates = rates();
        Map<String, BigDecimal> rateIndex = new HashMap<>();
        for (ProvisionReportResponse.Rate r : rates) {
            rateIndex.put(r.typeCode() + "@" + r.daysPastDue(), r.lossRate());
        }

        List<ProvisionReportResponse.Allowance> allowance = new ArrayList<>();
        BigDecimal totalOutstanding = BigDecimal.ZERO;
        BigDecimal totalProvision = BigDecimal.ZERO;

        for (Map<String, Object> row : jdbc.queryForList(ALLOWANCE_SQL)) {
            String code = (String) row.get("code");
            String bucket = (String) row.get("bucket");
            BigDecimal outstanding = money(row.get("outstanding"));
            // No history for this type+band means no basis for a rate. Provide nothing rather than
            // invent a number — an unfounded provision is worse than a visible gap.
            BigDecimal lossRate = rateIndex.getOrDefault(
                    code + "@" + BUCKET_THRESHOLD.get(bucket), BigDecimal.ZERO);
            BigDecimal provision = outstanding.multiply(lossRate).setScale(2, RoundingMode.HALF_UP);
            allowance.add(new ProvisionReportResponse.Allowance(
                    code, (String) row.get("name"), bucket, outstanding, lossRate, provision,
                    ((Number) row.get("invoices")).longValue()));
            totalOutstanding = totalOutstanding.add(outstanding);
            totalProvision = totalProvision.add(provision);
        }

        return new ProvisionReportResponse(LocalDate.now(clock), MATURITY_DAYS, rates, allowance,
                totalOutstanding, totalProvision);
    }

    private List<ProvisionReportResponse.Rate> rates() {
        List<ProvisionReportResponse.Rate> out = new ArrayList<>();
        for (Map<String, Object> row : jdbc.queryForList(RATE_SQL, MATURITY_DAYS)) {
            BigDecimal reached = money(row.get("reached"));
            BigDecimal recovered = money(row.get("recovered"));
            BigDecimal lossRate = reached.signum() == 0
                    ? BigDecimal.ZERO
                    : BigDecimal.ONE.subtract(
                            recovered.divide(reached, 4, RoundingMode.HALF_UP)).max(BigDecimal.ZERO);
            out.add(new ProvisionReportResponse.Rate(
                    (String) row.get("type_code"), (String) row.get("type_name"),
                    ((Number) row.get("threshold")).intValue(), reached, recovered, lossRate,
                    ((Number) row.get("invoices")).longValue()));
        }
        return out;
    }

    private static BigDecimal money(Object value) {
        return value == null ? BigDecimal.ZERO : ((BigDecimal) value).setScale(2, RoundingMode.HALF_UP);
    }
}
