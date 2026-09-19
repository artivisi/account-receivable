package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.service.DashboardService;
import com.artivisi.accountreceivable.service.ReceivableAnomalyService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class AdminDashboardController {

    private final DashboardService dashboardService;
    private final ReceivableAnomalyService anomalyService;

    public AdminDashboardController(DashboardService dashboardService, ReceivableAnomalyService anomalyService) {
        this.dashboardService = dashboardService;
        this.anomalyService = anomalyService;
    }

    @GetMapping("/admin")
    public String dashboard(Model model) {
        model.addAttribute("summary", dashboardService.summary());
        // Findings raised outside AR leave every figure on this page looking right, so their count is
        // announced on the page finance opens first.
        model.addAttribute("openAnomalyCount", anomalyService.countOpen());
        return "admin/dashboard";
    }
}
