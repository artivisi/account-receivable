package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.AgingReportResponse;
import com.artivisi.accountreceivable.service.AgingService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports/aging")
public class AgingController {

    /** Size of the overdue leaderboard this endpoint returns; drill-down is /api/invoices. */
    private static final int TOP_OVERDUE_LIMIT = 20;

    private final AgingService service;

    public AgingController(AgingService service) {
        this.service = service;
    }

    @GetMapping
    public AgingReportResponse report() {
        return service.report(TOP_OVERDUE_LIMIT);
    }
}
