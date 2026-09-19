package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.dto.NotificationRequest;

/**
 * Seam over the notification transport. The production impl publishes to the hub's Kafka topic;
 * tests inject a capturing fake and drive dispatch explicitly. Throwing propagates to the dispatcher
 * for retry (the outbox row stays PENDING with backoff).
 */
public interface NotificationPublisher {

    void publish(NotificationRequest request);
}
