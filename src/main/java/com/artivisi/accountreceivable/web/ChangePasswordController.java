package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.service.OperatorService;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** Self-service password change; also the landing page of a forced change. */
@Controller
public class ChangePasswordController {

    private final OperatorService operatorService;

    public ChangePasswordController(OperatorService operatorService) {
        this.operatorService = operatorService;
    }

    @GetMapping("/change-password")
    public String form() {
        return "change-password";
    }

    @PostMapping("/change-password")
    public String change(Authentication authentication,
                         @RequestParam String currentPassword,
                         @RequestParam String newPassword,
                         @RequestParam String confirmPassword,
                         Model model) {
        if (!newPassword.equals(confirmPassword)) {
            model.addAttribute("error", "New password and confirmation do not match");
            return "change-password";
        }
        try {
            operatorService.changeOwnPassword(authentication.getName(), currentPassword, newPassword);
        } catch (RuntimeException e) {
            model.addAttribute("error", e.getMessage());
            return "change-password";
        }
        return "redirect:/admin";
    }
}
