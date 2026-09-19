package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.CashApplicationListItem;
import com.artivisi.accountreceivable.entity.CashApplication;
import com.artivisi.accountreceivable.entity.CashApplicationStatus;
import com.artivisi.accountreceivable.service.ReceivableReviewService;
import com.artivisi.accountreceivable.service.CollectionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/cash-applications")
public class CashApplicationAdminController {

    private static final int SIZE = 20;

    private final CollectionService service;
    private final ReceivableReviewService reviewService;

    public CashApplicationAdminController(CollectionService service,
                                          ReceivableReviewService reviewService) {
        this.service = service;
        this.reviewService = reviewService;
    }

    @GetMapping
    public String list(@RequestParam(required = false) CashApplicationStatus status,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<CashApplicationListItem> pageInfo = service.listCashApplications(
                status, PageRequest.of(page, SIZE, Sort.by("receivedAt").descending()));
        model.addAttribute("cashApplications", pageInfo.getContent());
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("unappliedCount", service.countUnappliedCashApplications());
        model.addAttribute("statusFilter", status);
        model.addAttribute("upstreamMissingCount", reviewService.countUpstreamMissing());
        return "admin/cash-application/list";
    }

    /**
     * Payments the originating billing system never booked. A separate worklist rather than a filter
     * on the table above, because the row's problem is not its own status — every one of these is
     * APPLIED and correct here — and the action is different: someone records it in the other
     * system, then says so.
     */
    @GetMapping("/upstream-missing")
    public String upstreamMissing(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<CashApplication> result = reviewService.upstreamMissing(
                PageRequest.of(Math.max(page, 0), SIZE));
        model.addAttribute("rows", result.getContent());
        model.addAttribute("pageInfo", result);
        model.addAttribute("total", reviewService.countUpstreamMissing());
        return "admin/cash-application/upstream-missing";
    }

    @PostMapping("/{id}/upstream-cleared")
    public String clearUpstream(@PathVariable String id,
                                @RequestParam(required = false) String note,
                                RedirectAttributes ra) {
        try {
            reviewService.clearUpstreamMissing(id, note);
            ra.addFlashAttribute("msg", "Ditandai sudah dicatat di aplikasi penagih");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/cash-applications/upstream-missing";
    }
}
