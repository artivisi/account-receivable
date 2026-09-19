package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.DebtorRequest;
import com.artivisi.accountreceivable.dto.DebtorResponse;
import com.artivisi.accountreceivable.entity.Debtor;
import com.artivisi.accountreceivable.entity.DebtorStatus;
import com.artivisi.accountreceivable.service.DebtorLedgerService;
import com.artivisi.accountreceivable.service.DebtorService;
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
@RequestMapping("/admin/debtors")
public class DebtorAdminController {

    private final DebtorService service;
    private final DebtorLedgerService debtorLedgerService;

    public DebtorAdminController(DebtorService service, DebtorLedgerService debtorLedgerService) {
        this.service = service;
        this.debtorLedgerService = debtorLedgerService;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page, Model model) {
        Page<DebtorResponse> pageInfo = service
                .list(q, PageRequest.of(page, 20, Sort.by("code").ascending()))
                .map(DebtorResponse::from);
        model.addAttribute("debtors", pageInfo.getContent());
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("q", q);
        return "admin/debtor/list";
    }

    /** HTMX typeahead source for the debtor picker — top matches only, never a full load. */
    @GetMapping("/lookup")
    public String lookup(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("debtors", service
                .list(q, PageRequest.of(0, 10, Sort.by("code").ascending()))
                .map(DebtorResponse::from).getContent());
        return "admin/debtor/lookup-results :: results";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("debtor", null);
        model.addAttribute("statuses", DebtorStatus.values());
        model.addAttribute("mode", "new");
        return "admin/debtor/form";
    }

    @PostMapping
    public String create(@RequestParam String code, @RequestParam String name,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam DebtorStatus status, RedirectAttributes ra) {
        try {
            service.create(new DebtorRequest(code, name, email, phone, status));
            ra.addFlashAttribute("msg", "Debitur " + code + " berhasil dibuat");
            return "redirect:/admin/debtors";
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/debtors/new";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, @RequestParam(defaultValue = "6") int months, Model model) {
        Debtor debtor = service.get(id);
        model.addAttribute("detail", debtorLedgerService.detail(debtor.getCode(), months));
        model.addAttribute("debtorId", debtor.getId());
        return "admin/debtor/detail";
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        model.addAttribute("debtor", DebtorResponse.from(service.get(id)));
        model.addAttribute("statuses", DebtorStatus.values());
        model.addAttribute("mode", "edit");
        return "admin/debtor/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @RequestParam String name,
                         @RequestParam(required = false) String email,
                         @RequestParam(required = false) String phone,
                         @RequestParam DebtorStatus status, RedirectAttributes ra) {
        service.update(id, new DebtorRequest(null, name, email, phone, status));
        ra.addFlashAttribute("msg", "Debitur berhasil diperbarui");
        return "redirect:/admin/debtors";
    }
}
