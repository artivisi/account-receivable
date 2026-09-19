package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.service.AuditService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.artivisi.accountreceivable.entity.AuditEvent;

@Controller
@RequestMapping("/admin/audit")
public class AuditAdminController {

    private static final int SIZE = 25;

    private final AuditService service;

    public AuditAdminController(AuditService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<AuditEvent> pageInfo = service.page(q, PageRequest.of(page, SIZE, Sort.by("createdAt").descending()));
        model.addAttribute("events", pageInfo.getContent());
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("q", q);
        return "admin/audit/list";
    }
}
