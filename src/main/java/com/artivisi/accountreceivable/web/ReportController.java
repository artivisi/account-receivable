package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.DebtorStatementResponse;
import com.artivisi.accountreceivable.dto.RecapResponse;
import com.artivisi.accountreceivable.service.ReportService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final ReportService service;

    public ReportController(ReportService service) {
        this.service = service;
    }

    @GetMapping("/statement/{debtorCode}")
    public DebtorStatementResponse statement(@PathVariable String debtorCode) {
        return service.statement(debtorCode);
    }

    @GetMapping("/recap")
    public RecapResponse recap() {
        return service.recap();
    }
}
