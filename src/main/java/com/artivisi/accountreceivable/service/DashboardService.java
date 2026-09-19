package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.AgingReportResponse;
import com.artivisi.accountreceivable.dto.DashboardSummaryResponse;
import com.artivisi.accountreceivable.entity.AgingBucket;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import com.artivisi.accountreceivable.repository.CashApplicationRepository;
import com.artivisi.accountreceivable.repository.InvoiceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

/**
 * Composes the admin dashboard summary: aging (delegated to {@link AgingService}), overdue/received
 * roll-ups aggregated in SQL, and a DSO approximation. The roll-ups previously iterated
 * {@code findAll()} over cash applications and invoices; at production volume that exhausted the heap
 * and took the application down (2026-08-18), so they are counts and sums in the database now.
 *
 * <p>DSO uses the standard balance-sheet formula (outstanding ÷ trailing
 * {@value #DSO_WINDOW_DAYS}-day credit sales × {@value #DSO_WINDOW_DAYS}) rather than tracking
 * per-invoice payment dates, which this subledger does not store.
 */
@Service
public class DashboardService {

    private static final int TOP_OVERDUE_LIMIT = 5;
    private static final int RECENT_ACTIVITY_LIMIT = 8;
    private static final int DSO_WINDOW_DAYS = 30;

    private final AgingService agingService;
    private final InvoiceRepository invoiceRepository;
    private final CashApplicationRepository cashApplicationRepository;
    private final AuditService auditService;
    private final Clock clock;

    public DashboardService(AgingService agingService, InvoiceRepository invoiceRepository,
                            CashApplicationRepository cashApplicationRepository,
                            AuditService auditService, Clock clock) {
        this.agingService = agingService;
        this.invoiceRepository = invoiceRepository;
        this.cashApplicationRepository = cashApplicationRepository;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public DashboardSummaryResponse summary() {
        AgingReportResponse aging = agingService.report(TOP_OVERDUE_LIMIT);
        LocalDate asOf = aging.asOf();

        BigDecimal overdueTotal = BigDecimal.ZERO;
        long overdueCount = 0;
        for (AgingReportResponse.Bucket b : aging.buckets()) {
            if (b.bucket() != AgingBucket.CURRENT) {
                overdueTotal = overdueTotal.add(b.amount());
                overdueCount += b.count();
            }
        }

        // Both come from the aging query itself now — distinct debtors counted in SQL, and the
        // leaderboard ordered and limited there, rather than derived from a full entry list.
        long openDebtorCount = aging.openDebtorCount();
        List<AgingReportResponse.Entry> topOverdue = aging.topOverdue();

        // The month as a half-open instant range in the system zone, matching the zone the previous
        // per-row YearMonth comparison used. A null received_at simply falls outside the range.
        YearMonth thisMonth = YearMonth.from(asOf);
        ZoneId zone = ZoneId.systemDefault();
        Instant monthStart = thisMonth.atDay(1).atStartOfDay(zone).toInstant();
        Instant nextMonthStart = thisMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant();

        long receivedThisMonthCount = cashApplicationRepository
                .countByStatusReceivedBetween(CashApplicationStatus.APPLIED, monthStart, nextMonthStart);
        BigDecimal receivedThisMonthTotal = emptySumAsZero(cashApplicationRepository
                .sumAmountByStatusReceivedBetween(CashApplicationStatus.APPLIED, monthStart, nextMonthStart));
        long unappliedThisMonthCount = cashApplicationRepository
                .countByStatusReceivedBetween(CashApplicationStatus.UNAPPLIED, monthStart, nextMonthStart);

        LocalDate windowStart = asOf.minusDays(DSO_WINDOW_DAYS);
        BigDecimal creditSalesWindow = emptySumAsZero(
                invoiceRepository.sumAmountIssuedBetween(windowStart, asOf));
        BigDecimal dso = creditSalesWindow.signum() == 0
                ? BigDecimal.ZERO
                : aging.totalOutstanding()
                        .multiply(BigDecimal.valueOf(DSO_WINDOW_DAYS))
                        .divide(creditSalesWindow, 0, RoundingMode.HALF_UP);

        return new DashboardSummaryResponse(asOf, aging, overdueTotal, overdueCount, openDebtorCount,
                receivedThisMonthTotal, receivedThisMonthCount, unappliedThisMonthCount, dso,
                topOverdue, auditService.recent().stream().limit(RECENT_ACTIVITY_LIMIT).toList());
    }

    /**
     * SQL sums to null over an empty window. That is the empty-sum identity — "nothing was received
     * this month" — not a missing or defaulted value, and it is what the accumulate-from-zero loops
     * these queries replaced already produced.
     */
    private static BigDecimal emptySumAsZero(BigDecimal sum) {
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
