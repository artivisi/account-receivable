package com.artivisi.accountreceivable.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps every live instalment-plan charge saying what its plan says today.
 *
 * <p>An installment that passes its due date unpaid changes what the plan's VA must answer, and
 * nothing else in the system runs at that moment — no request, no webhook. So this polls: for each
 * plan with a live charge, recompute the amount due and reprice at the gateway if it moved.
 * Idempotent, so the interval only bounds how long the ATM can lag the plan.
 *
 * <p>Same shape as {@link SupersededChargeSweeper}: poll, act per invoice in its own transaction.
 * Tests disable the poller (large interval) and call {@link #sweep()} explicitly.
 */
@Component
public class InstallmentRepricer {

    private final CollectionService service;

    public InstallmentRepricer(CollectionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ar.gateway.poll-interval-ms}",
            initialDelayString = "${ar.gateway.poll-interval-ms}")
    public void sweep() {
        for (String invoiceId : service.findPlanInvoicesToSync()) {
            service.syncPlanCharge(invoiceId);
        }
    }
}
