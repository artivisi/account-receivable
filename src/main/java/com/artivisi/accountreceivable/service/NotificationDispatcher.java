package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.entity.NotificationOutbox;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the notification outbox: polls due rows and publishes each in its own transaction. Tests
 * disable the poller (large interval) and call {@link #dispatchDue()} explicitly.
 */
@Component
public class NotificationDispatcher {

    private final NotificationService service;

    public NotificationDispatcher(NotificationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${ar.notification.poll-interval-ms}",
            initialDelayString = "${ar.notification.poll-interval-ms}")
    public void dispatchDue() {
        for (NotificationOutbox due : service.findDue()) {
            service.publish(due.getId());
        }
    }
}
