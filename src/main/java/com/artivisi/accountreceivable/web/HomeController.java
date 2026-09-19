package com.artivisi.accountreceivable.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "redirect:/admin";
    }

    @GetMapping("/login")
    public String login() {
        return "login";
    }
}
