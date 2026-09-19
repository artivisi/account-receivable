package com.artivisi.accountreceivable.web;

import com.artivisi.accountreceivable.service.ReceivableReviewService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Where an external reconciliation check reports that the originating billing system never booked a
 * payment we accepted.
 *
 * <p>Keyed on the gateway payment reference rather than our own id, because that reference is the
 * only identifier the two systems share — a checker comparing them has it in hand and should not
 * have to look anything up here first.
 */
@RestController
public class UpstreamAckController {

    private final ReceivableReviewService service;

    public UpstreamAckController(ReceivableReviewService service) {
        this.service = service;
    }

    /** Idempotent per reference, so a daily check can re-report an unresolved divergence safely. */
    @PostMapping("/api/cash-applications/{reference}/upstream-missing")
    public ResponseEntity<Void> flagMissing(@PathVariable String reference,
                                            @RequestParam String note) {
        service.flagUpstreamMissing(reference, note);
        return ResponseEntity.noContent().build();
    }
}
