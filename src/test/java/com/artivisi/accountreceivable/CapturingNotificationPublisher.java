package com.artivisi.accountreceivable;

import com.artivisi.accountreceivable.dto.NotificationRequest;
import com.artivisi.accountreceivable.service.NotificationPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test double for {@link NotificationPublisher}: captures published requests instead of hitting a
 * broker. Can be primed to throw, to exercise the outbox retry/backoff path.
 */
public class CapturingNotificationPublisher implements NotificationPublisher {

    private final List<NotificationRequest> published = new CopyOnWriteArrayList<>();
    private volatile boolean failing = false;

    @Override
    public void publish(NotificationRequest request) {
        if (failing) {
            throw new IllegalStateException("stub publish failure");
        }
        published.add(request);
    }

    public List<NotificationRequest> published() {
        return published;
    }

    public void reset() {
        published.clear();
        failing = false;
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }
}
