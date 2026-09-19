package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.AnomalyQueueItem;
import com.artivisi.accountreceivable.service.ReceivableAnomalyService;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The worklist for findings raised from outside AR. Separate from the review queue because the
 * evidence comes from somewhere AR cannot see, and one of the actions books money.
 */
@Controller
@RequestMapping("/admin/anomalies")
public class ReceivableAnomalyAdminController {

    private static final int PAGE_SIZE = 20;

    /** Labels for the decisions offered, keyed by the codes the service accepts, in its order. */
    private static final Map<String, String> RESOLUTION_LABELS = resolutionLabels();

    private final ReceivableAnomalyService service;

    public ReceivableAnomalyAdminController(ReceivableAnomalyService service) {
        this.service = service;
    }

    @GetMapping
    public String list(@RequestParam(defaultValue = "0") int page, Model model) {
        Page<AnomalyQueueItem> result = service.openQueue(page, PAGE_SIZE);
        model.addAttribute("rows", result.getContent());
        model.addAttribute("pageInfo", result);
        model.addAttribute("total", result.getTotalElements());
        model.addAttribute("resolutions", RESOLUTION_LABELS);
        return "admin/anomaly/list";
    }

    @PostMapping("/{id}/resolve")
    public String resolve(@PathVariable String id,
                          @RequestParam(required = false) String resolution,
                          @RequestParam(required = false) String note,
                          Principal principal, RedirectAttributes ra) {
        try {
            service.resolve(id, resolution, note, operator(principal));
            ra.addFlashAttribute("msg", "Temuan ditutup dan keputusannya tercatat");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/anomalies";
    }

    @PostMapping("/{id}/book-payment")
    public String bookPayment(@PathVariable String id,
                              @RequestParam(required = false) String note,
                              Principal principal, RedirectAttributes ra) {
        try {
            service.bookPayment(id, note, operator(principal));
            ra.addFlashAttribute("msg", "Pembayaran dicatat ke faktur dan aplikasi penagih diberi tahu");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/anomalies";
    }

    /** The person deciding. /admin requires a login, so a missing principal is a wiring fault. */
    private static String operator(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new IllegalStateException("No authenticated operator on an /admin request");
        }
        return principal.getName();
    }

    private static Map<String, String> resolutionLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String code : ReceivableAnomalyService.MANUAL_RESOLUTIONS) {
            labels.put(code, switch (code) {
                case "ALREADY_RECORDED" -> "Sudah tercatat di tempat lain";
                case "REFUNDED" -> "Dana dikembalikan ke pembayar";
                case "BANK_REVERSED" -> "Transaksi dibatalkan bank";
                case "NOT_THIS_RECEIVABLE" -> "Pembayaran milik piutang lain";
                case "OTHER" -> "Lainnya, jelaskan di catatan";
                default -> throw new IllegalStateException("No label for resolution " + code);
            });
        }
        return labels;
    }
}
