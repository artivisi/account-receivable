package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.entity.UserRole;
import com.artivisi.accountreceivable.service.OperatorService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/operators")
public class OperatorAdminController {

    private final OperatorService service;

    public OperatorAdminController(OperatorService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("operators", service.list());
        model.addAttribute("roles", UserRole.values());
        return "admin/operator/list";
    }

    @PostMapping
    public String create(@RequestParam String username, @RequestParam String displayName,
                         @RequestParam UserRole role, @RequestParam String password, RedirectAttributes ra) {
        try {
            service.create(username, displayName, role, password);
            ra.addFlashAttribute("msg", "Operator " + username + " berhasil dibuat");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/operators";
    }

    @PostMapping("/{id}/enabled")
    public String setEnabled(@PathVariable String id, @RequestParam boolean enabled, RedirectAttributes ra) {
        service.setEnabled(id, enabled);
        ra.addFlashAttribute("msg", "Operator updated");
        return "redirect:/admin/operators";
    }

    @PostMapping("/{id}/reset-password")
    public String resetPassword(@PathVariable String id, @RequestParam String password, RedirectAttributes ra) {
        try {
            service.resetPassword(id, password);
            ra.addFlashAttribute("msg", "Temporary password set; the operator must change it at next login");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/operators";
    }
}
