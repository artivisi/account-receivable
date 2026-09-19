package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.entity.ChargeCancellation;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the charge-cancellation outbox: polls due rows and cancels each in its own transaction.
 * Tests disable the poller (large interval) and call {@link #dispatchDue()} explicitly.
 */
@Component
public class ChargeCancellationDispatcher {

    private final ChargeCancellationService service;

    public ChargeCancellationDispatcher(ChargeCancellationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ar.gateway.poll-interval-ms}",
            initialDelayString = "${ar.gateway.poll-interval-ms}")
    public void dispatchDue() {
        for (ChargeCancellation due : service.findDue()) {
            service.cancel(due.getId());
        }
    }
}
