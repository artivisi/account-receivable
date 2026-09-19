package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.DebtorStatementResponse;
import com.artivisi.accountreceivable.exception.NotFoundException;
import com.artivisi.accountreceivable.service.AgingService;
import com.artivisi.accountreceivable.service.ProvisionService;
import com.artivisi.accountreceivable.service.ReportService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/reports")
public class ReportAdminController {

    /** Overdue leaderboard shown alongside the bucket table on the aging page. */
    private static final int TOP_OVERDUE_LIMIT = 20;

    private final AgingService agingService;
    private final ReportService reportService;
    private final ProvisionService provisionService;

    public ReportAdminController(AgingService agingService, ReportService reportService,
                                 ProvisionService provisionService) {
        this.agingService = agingService;
        this.reportService = reportService;
        this.provisionService = provisionService;
    }

    @GetMapping("/aging")
    public String aging(Model model) {
        model.addAttribute("aging", agingService.report(TOP_OVERDUE_LIMIT));
        return "admin/reports/aging";
    }

    /** Penyisihan piutang tak tertagih — provision matrix from own history, applied to today's aging. */
    @GetMapping("/provision")
    public String provision(Model model) {
        model.addAttribute("report", provisionService.report());
        return "admin/reports/provision";
    }

    @GetMapping("/statement")
    public String statement(@RequestParam(required = false) String debtorCode, Model model) {
        model.addAttribute("debtorCode", debtorCode);
        if (debtorCode != null && !debtorCode.isBlank()) {
            try {
                DebtorStatementResponse statement = reportService.statement(debtorCode);
                model.addAttribute("statement", statement);
                // Prefill the picker's search field with the chosen debtor.
                model.addAttribute("selectedDebtorLabel",
                        statement.debtorCode() + " · " + statement.debtorName());
            } catch (NotFoundException e) {
                model.addAttribute("error", e.getMessage());
            }
        }
        return "admin/reports/statement";
    }

    @GetMapping("/recap")
    public String recap(Model model) {
        model.addAttribute("recap", reportService.recap());
        return "admin/reports/recap";
    }
}
