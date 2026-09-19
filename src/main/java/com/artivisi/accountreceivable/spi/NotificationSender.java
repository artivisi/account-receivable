package com.artivisi.accountreceivable.spi;

import com.artivisi.accountreceivable.entity.NotificationChannel;

/**
 * Delivers a dunning reminder over one channel. The engine ships <b>no</b> implementation — the
 * deployment provides a {@code @Component} per channel (email/SMS), rendering its own templates.
 * A run requesting a channel with no registered sender fails loud.
 */
public interface NotificationSender {

    NotificationChannel channel();

    /** Deliver the reminder. Throwing marks the reminder ERROR; returning marks it SENT. */
    void send(DunningNotification notification);
}
