package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.DunningRunRequest;
import com.artivisi.accountreceivable.entity.NotificationChannel;
import com.artivisi.accountreceivable.service.DunningService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/admin/dunning")
public class DunningAdminController {

    private final DunningService service;

    public DunningAdminController(DunningService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("runs", service.list());
        model.addAttribute("channels", NotificationChannel.values());
        return "admin/dunning/list";
    }

    @PostMapping
    public String run(@RequestParam NotificationChannel channel,
                      @RequestParam(defaultValue = "0") int minDaysOverdue, RedirectAttributes ra) {
        try {
            var response = service.run(new DunningRunRequest(channel, minDaysOverdue));
            ra.addFlashAttribute("msg", "Dunning run done: " + response.sentCount() + " sent, "
                    + response.errorCount() + " errors");
            return "redirect:/admin/dunning/" + response.id();
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/dunning";
        }
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        model.addAttribute("run", service.get(id));
        return "admin/dunning/detail";
    }
}
