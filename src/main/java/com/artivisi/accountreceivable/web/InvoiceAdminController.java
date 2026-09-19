package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.dto.ChargeResponse;
import com.artivisi.accountreceivable.dto.CreditNoteRequest;
import com.artivisi.accountreceivable.dto.DueDateOutcome;
import com.artivisi.accountreceivable.dto.InvoiceListItem;
import com.artivisi.accountreceivable.dto.ReviewQueueItem;
import com.artivisi.accountreceivable.dto.InvoiceResponse;
import com.artivisi.accountreceivable.dto.InvoiceTypeResponse;
import com.artivisi.accountreceivable.dto.IssueInvoiceRequest;
import com.artivisi.accountreceivable.entity.ChargeStatus;
import com.artivisi.accountreceivable.entity.CreditNote;
import com.artivisi.accountreceivable.repository.AuditEventRepository;
import com.artivisi.accountreceivable.repository.CreditNoteRepository;
import com.artivisi.accountreceivable.service.CollectionService;
import com.artivisi.accountreceivable.service.CreditNoteService;
import com.artivisi.accountreceivable.service.InvoiceService;
import com.artivisi.accountreceivable.service.InvoiceTypeService;
import com.artivisi.accountreceivable.service.ReceivableReviewService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Controller
@RequestMapping("/admin/invoices")
public class InvoiceAdminController {

    private static final int PAGE_SIZE = 20;

    private final InvoiceService invoiceService;
    private final CollectionService collectionService;
    private final CreditNoteService creditNoteService;
    private final InvoiceTypeService invoiceTypeService;
    private final ReceivableReviewService reviewService;
    private final com.artivisi.accountreceivable.service.ReceivableAnomalyService anomalyService;
    private final CreditNoteRepository creditNoteRepository;
    private final AuditEventRepository auditEventRepository;
    private final Clock clock;

    public InvoiceAdminController(InvoiceService invoiceService, CollectionService collectionService,
                                 CreditNoteService creditNoteService,
                                 InvoiceTypeService invoiceTypeService,
                                 ReceivableReviewService reviewService,
                                 com.artivisi.accountreceivable.service.ReceivableAnomalyService anomalyService,
                                 CreditNoteRepository creditNoteRepository,
                                 AuditEventRepository auditEventRepository, Clock clock) {
        this.invoiceService = invoiceService;
        this.collectionService = collectionService;
        this.creditNoteService = creditNoteService;
        this.invoiceTypeService = invoiceTypeService;
        this.reviewService = reviewService;
        this.anomalyService = anomalyService;
        this.creditNoteRepository = creditNoteRepository;
        this.auditEventRepository = auditEventRepository;
        this.clock = clock;
    }

    @GetMapping
    public String list(@RequestParam(required = false) String status,
                       @RequestParam(required = false) String type,
                       @RequestParam(required = false) String q,
                       @RequestParam(required = false) String bucket,
                       @RequestParam(defaultValue = "0") int page,
                       Model model) {
        Page<InvoiceListItem> result = invoiceService.searchPage(status, type, q, bucket,
                PageRequest.of(Math.max(page, 0), PAGE_SIZE, Sort.by("invoiceNumber").descending()));

        model.addAttribute("invoices", result.getContent());
        model.addAttribute("pageInfo", result);
        model.addAttribute("status", status);
        model.addAttribute("type", type);
        model.addAttribute("q", q);
        model.addAttribute("bucket", bucket);
        model.addAttribute("today", LocalDate.now(clock));
        model.addAttribute("types", invoiceTypeService.list().stream().map(InvoiceTypeResponse::from).toList());
        return "admin/invoice/list";
    }

    /** Default review window: a week, which matches the routine this is built for. */
    private static final int DEFAULT_QUEUE_DAYS = 7;

    /** Windows offered on the queue. Built here rather than in the template, where an inline list
     *  literal is an Object[] and blows up on the way into a String[]. */
    private static final List<Integer> QUEUE_WINDOWS = List.of(7, 30, 90, 365);

    @GetMapping("/write-off-queue")
    public String writeOffQueue(@RequestParam(required = false) Integer days,
                                @RequestParam(defaultValue = "0") int page,
                                Model model) {
        int window = days == null ? DEFAULT_QUEUE_DAYS : days;
        int current = Math.max(page, 0);
        List<ReviewQueueItem> rows = reviewService.queue(window, current, PAGE_SIZE);
        long total = reviewService.count(window);
        model.addAttribute("queue", rows);
        model.addAttribute("total", total);
        model.addAttribute("page", current);
        model.addAttribute("hasPrev", current > 0);
        model.addAttribute("hasNext", (long) (current + 1) * PAGE_SIZE < total);
        model.addAttribute("days", window);
        model.addAttribute("windows", QUEUE_WINDOWS);
        return "admin/invoice/write-off-queue";
    }

    /**
     * "Looked at it, still worth collecting." Drops the row from the queue and records why, so a
     * deliberate decision is not re-presented tomorrow as an unread one.
     */
    @PostMapping("/{id}/keep")
    public String keepCollecting(@PathVariable String id,
                                 @RequestParam(required = false) String note,
                                 RedirectAttributes ra) {
        try {
            reviewService.keepCollecting(id, note);
            ra.addFlashAttribute("msg", "Ditandai masih tertagih");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/invoices/write-off-queue";
    }

    @GetMapping("/{id}")
    public String detail(@PathVariable String id, Model model) {
        InvoiceResponse invoice = invoiceService.get(id);
        model.addAttribute("invoice", invoice);
        List<CreditNote> creditNotes = creditNoteRepository.findByInvoiceId(id);
        BigDecimal creditNoteTotal = creditNotes.stream()
                .map(CreditNote::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paidAmount = invoice.amount().subtract(invoice.outstanding()).subtract(creditNoteTotal);
        BigDecimal paidPercent = invoice.amount().signum() == 0 ? BigDecimal.ZERO
                : invoice.amount().subtract(invoice.outstanding())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(invoice.amount(), 0, RoundingMode.HALF_UP);
        model.addAttribute("creditNoteTotal", creditNoteTotal);
        model.addAttribute("paidAmount", paidAmount);
        model.addAttribute("paidPercent", paidPercent);
        model.addAttribute("history",
                auditEventRepository.findByEntityTypeAndEntityIdOrderByCreatedAtDesc("Invoice", id));
        // The warning belongs where the operator is, not only in the review queue. A receivable that
        // reads "OPEN, overdue" is an instruction to collect, and nobody consults a second screen to
        // check an instruction they were just given.
        reviewService.evidenceFor(id).ifPresent(e -> model.addAttribute("evidence", e));
        // Findings raised outside AR, for the same reason: this invoice looks normal from in here.
        model.addAttribute("anomalies", anomalyService.openForInvoice(id));
        if (invoice.overdue()) {
            model.addAttribute("daysOverdue", daysOverdue(invoice.dueDate()));
        }
        return "admin/invoice/detail";
    }

    private long daysOverdue(LocalDate dueDate) {
        LocalDate today = LocalDate.now(clock);
        return dueDate.isBefore(today) ? ChronoUnit.DAYS.between(dueDate, today) : 0L;
    }

    @GetMapping("/new")
    public String newForm(Model model) {
        model.addAttribute("today", LocalDate.now());
        return "admin/invoice/form";
    }

    @PostMapping
    public String issue(@RequestParam String debtorCode, @RequestParam String invoiceTypeCode,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issueDate,
                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                        @RequestParam(required = false) String description,
                        @RequestParam(required = false) List<String> lineDescription,
                        @RequestParam(required = false) List<BigDecimal> lineQuantity,
                        @RequestParam(required = false) List<BigDecimal> lineUnitAmount,
                        @RequestParam(defaultValue = "false") boolean installment,
                        @RequestParam(required = false) List<String> installmentDueDate,
                        @RequestParam(required = false) List<BigDecimal> installmentAmount,
                        RedirectAttributes ra) {
        try {
            List<IssueInvoiceRequest.LineRequest> lines = new ArrayList<>();
            if (lineDescription != null) {
                for (int i = 0; i < lineDescription.size(); i++) {
                    String desc = lineDescription.get(i);
                    BigDecimal qty = at(lineQuantity, i);
                    BigDecimal unit = at(lineUnitAmount, i);
                    if (desc == null || desc.isBlank() || qty == null || unit == null) {
                        continue;
                    }
                    lines.add(new IssueInvoiceRequest.LineRequest(desc, qty, unit));
                }
            }
            List<IssueInvoiceRequest.InstallmentRequest> installments = null;
            if (installment && installmentDueDate != null) {
                installments = new ArrayList<>();
                for (int i = 0; i < installmentDueDate.size(); i++) {
                    String due = installmentDueDate.get(i);
                    BigDecimal amt = at(installmentAmount, i);
                    if (due == null || due.isBlank() || amt == null) {
                        continue;
                    }
                    installments.add(new IssueInvoiceRequest.InstallmentRequest(LocalDate.parse(due), amt));
                }
            }
            // A plan expires with its last instalment, and the service refuses any other date.
            LocalDate effectiveDueDate = installments == null ? dueDate
                    : installments.stream().map(IssueInvoiceRequest.InstallmentRequest::dueDate)
                            .max(java.util.Comparator.naturalOrder()).orElse(dueDate);
            var response = invoiceService.issue(new IssueInvoiceRequest(
                    debtorCode, invoiceTypeCode, issueDate, effectiveDueDate, description, lines, installments));
            ra.addFlashAttribute("msg", "Invoice " + response.invoiceNumber() + " issued");
            return "redirect:/admin/invoices/" + response.id();
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
            return "redirect:/admin/invoices/new";
        }
    }

    @PostMapping("/{id}/write-off")
    public String writeOff(@PathVariable String id,
                           @RequestParam(required = false) String reason,
                           @RequestParam(required = false) String from,
                           RedirectAttributes ra) {
        try {
            invoiceService.writeOff(id, reason);
            ra.addFlashAttribute("msg", "Invoice written off");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        // Decided from the queue: go back to the queue. Bouncing a reviewer working a list of
        // twenty into a detail page after each one is how a review session gets abandoned.
        return "queue".equals(from)
                ? "redirect:/admin/invoices/write-off-queue"
                : "redirect:/admin/invoices/" + id;
    }

    @PostMapping("/{id}/charge")
    public String openCharge(@PathVariable String id, RedirectAttributes ra) {
        try {
            ChargeResponse charge = collectionService.openChargeForInvoice(id);
            // openCharge is idempotent per target, so an invoice whose charge is already dead gets
            // that dead charge back rather than a new one. Saying "opened" there is a no-op wearing
            // a success message — the operator walks away believing the student can pay.
            if (charge.status() == ChargeStatus.ACTIVE || charge.status() == ChargeStatus.PARTIALLY_PAID) {
                ra.addFlashAttribute("msg", "Charge berhasil dibuka. VA " + charge.vaNumber());
            } else {
                ra.addFlashAttribute("error", "Tidak ada charge baru dibuka: faktur ini sudah punya charge"
                        + " berstatus " + charge.status() + " pada VA " + charge.vaNumber()
                        + ". Piutang ini tetap tidak bisa dibayar — periksa tagihan pengganti"
                        + " yang memakai nomor VA tersebut.");
            }
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/invoices/" + id;
    }


    /**
     * Move a deadline that has passed. Expiry is soft — the debt is never voided — so a payer who
     * turns up late needs the date moved, nothing reinstated. The gateway reactivates a VA its
     * sweep retired as part of the same call.
     */
    @PostMapping("/{id}/due-date")
    public String amendDueDate(@PathVariable String id,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                               RedirectAttributes ra) {
        try {
            report(collectionService.amendDueDate(id, dueDate), ra);
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/invoices/" + id;
    }

    @PostMapping("/{invoiceId}/installments/{installmentId}/due-date")
    public String amendInstallmentDueDate(@PathVariable String invoiceId, @PathVariable String installmentId,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDate,
                                          RedirectAttributes ra) {
        try {
            collectionService.amendInstallmentDueDate(installmentId, dueDate);
            ra.addFlashAttribute("msg", "Jatuh tempo cicilan diperbarui");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/invoices/" + invoiceId;
    }

    @PostMapping("/{id}/credit-notes")
    public String creditNote(@PathVariable String id, @RequestParam BigDecimal amount,
                             @RequestParam(required = false) String reason, RedirectAttributes ra) {
        try {
            creditNoteService.issue(new CreditNoteRequest(id, amount, reason));
            ra.addFlashAttribute("msg", "Nota kredit berhasil diterbitkan");
        } catch (RuntimeException e) {
            ra.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/admin/invoices/" + id;
    }

    /**
     * Turn what the amendment achieved into what the operator is told. The unpayable case is an
     * error, not a note: the date moved, but the thing they were trying to do did not happen.
     */
    private static void report(DueDateOutcome outcome, RedirectAttributes ra) {
        switch (outcome.kind()) {
            case VA_RESTORED -> ra.addFlashAttribute("msg",
                    "Jatuh tempo diperbarui, VA diaktifkan kembali. Debitur bisa membayar lagi.");
            case NO_CHARGE_YET -> ra.addFlashAttribute("msg",
                    "Jatuh tempo diperbarui. Faktur ini belum punya charge — klik Buka Charge"
                            + " untuk menerbitkan VA-nya.");
            case STILL_UNPAYABLE -> ra.addFlashAttribute("error",
                    "Jatuh tempo diperbarui, TETAPI piutang ini tetap tidak bisa dibayar."
                            + (outcome.blockingBillNumber() == null
                               ? " Charge-nya sudah dibatalkan dan tidak bisa dipulihkan dengan mengubah tanggal."
                               : " Nomor VA-nya sudah dipakai tagihan " + outcome.blockingBillNumber()
                                 + " yang berstatus " + outcome.blockingStatus()
                                 + " — utangnya ada di tagihan itu, bukan di faktur ini."));
        }
    }

    private static BigDecimal at(List<BigDecimal> list, int i) {
        return list != null && i < list.size() ? list.get(i) : null;
    }
}
