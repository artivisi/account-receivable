package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.InvoiceTypeRequest;
import com.artivisi.accountreceivable.dto.InvoiceTypeResponse;
import com.artivisi.accountreceivable.service.InvoiceTypeService;
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
@RequestMapping("/admin/invoice-types")
public class InvoiceTypeAdminController {

    private final InvoiceTypeService service;

    public InvoiceTypeAdminController(InvoiceTypeService service) {
        this.service = service;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("types", service.list().stream().map(InvoiceTypeResponse::from).toList());
        return "admin/invoice-type/list";
    }

    /** HTMX typeahead source for the invoice-form type picker — active, selectable types only. */
    @GetMapping("/lookup")
    public String lookup(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("types", service.search(q, true, PageRequest.of(0, 10, Sort.by("code").ascending()))
                .map(InvoiceTypeResponse::from).getContent());
        return "admin/invoice-type/lookup-results :: results";
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("type", null);
        model.addAttribute("mode", "new");
        return "admin/invoice-type/form";
    }

    @PostMapping
    public String create(@RequestParam String code, @RequestParam String name,
                         @RequestParam(defaultValue = "false") boolean active, RedirectAttributes ra) {
        try {
            service.create(new InvoiceTypeRequest(code, name, active));
            ra.addFlashAttribute("msg", "Tipe faktur " + code + " berhasil dibuat");
            return "redirect:/admin/invoice-types";
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/invoice-types/new";
        }
    }

    @GetMapping("/{id}/edit")
    public String editForm(@PathVariable String id, Model model) {
        model.addAttribute("type", InvoiceTypeResponse.from(service.get(id)));
        model.addAttribute("mode", "edit");
        return "admin/invoice-type/form";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable String id, @RequestParam String name,
                         @RequestParam(defaultValue = "false") boolean active, RedirectAttributes ra) {
        service.update(id, new InvoiceTypeRequest(null, name, active));
        ra.addFlashAttribute("msg", "Invoice type updated");
        return "redirect:/admin/invoice-types";
    }
}
