package com.artivisi.accountreceivable.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Puts superseded receivables back into collection once their VA number comes free.
 *
 * <p>Legacy reuses one VA number across successive bills of a debtor, so opening a new bill retires
 * the previous one's collection ({@link CollectionService} supersede path). Nothing put the previous
 * receivable back when the newer bill was paid, which left debts that AR reported OPEN, the billing
 * master reported AKTIF, and the gateway answered NOT_FOUND for.
 *
 * <p>Same shape as {@link ChargeCancellationDispatcher}: poll, then act on each row in its own
 * transaction. Tests disable the poller (large interval) and call {@link #sweep()} explicitly.
 */
@Component
public class SupersededChargeSweeper {

    private final CollectionService service;

    public SupersededChargeSweeper(CollectionService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ar.gateway.poll-interval-ms}",
            initialDelayString = "${ar.gateway.poll-interval-ms}")
    public void sweep() {
        for (String chargeId : service.findSupersededReceivablesToReopen()) {
            service.reopenSupersededReceivable(chargeId);
        }
    }
}
