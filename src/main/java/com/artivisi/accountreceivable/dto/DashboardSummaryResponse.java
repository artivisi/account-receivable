package com.artivisi.accountreceivable.dto;

import com.artivisi.accountreceivable.entity.AuditEvent;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Composed dashboard summary: aging snapshot + roll-ups + a DSO approximation + recent activity. */
public record DashboardSummaryResponse(
        LocalDate asOf,
        AgingReportResponse aging,
        BigDecimal overdueTotal,
        long overdueCount,
        long openDebtorCount,
        BigDecimal receivedThisMonthTotal,
        long receivedThisMonthCount,
        long unappliedThisMonthCount,
        BigDecimal dso,
        List<AgingReportResponse.Entry> topOverdue,
        List<AuditEvent> recentActivity
) {
}
