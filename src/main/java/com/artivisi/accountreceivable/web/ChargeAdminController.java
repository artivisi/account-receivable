package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.ChargeListItem;
import com.artivisi.accountreceivable.service.CollectionService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequestMapping("/admin/charges")
public class ChargeAdminController {

    private static final int SIZE = 20;

    private final CollectionService service;

    public ChargeAdminController(CollectionService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String q,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        // Unsorted on purpose: ChargeRepository.search fixes the order-by (newest first) in JPQL.
        Page<ChargeListItem> pageInfo = service.listCharges(q, PageRequest.of(page, SIZE));
        model.addAttribute("charges", pageInfo.getContent());
        model.addAttribute("pageInfo", pageInfo);
        model.addAttribute("q", q);
        return "admin/charge/list";
    }
}
