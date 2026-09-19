package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArNotificationProperties;
import com.artivisi.accountreceivable.entity.NotificationChannel;
import com.artivisi.accountreceivable.entity.NotificationSourceType;
import com.artivisi.accountreceivable.service.notification.NotificationPayloadMapper;
import com.artivisi.accountreceivable.spi.DunningNotification;
import com.artivisi.accountreceivable.spi.NotificationSender;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link NotificationSender} adapter that routes dunning reminders through the notification outbox to
 * the hub, instead of rendering/delivering itself. One instance per channel (see
 * {@code NotificationSenderConfig}). "SENT" from AR's perspective means "durably queued" — the hub
 * owns actual delivery.
 */
public class KafkaNotificationSender implements NotificationSender {

    private final NotificationChannel channel;
    private final NotificationService notificationService;
    private final ArNotificationProperties properties;
    private final NotificationPayloadMapper payloadMapper;

    public KafkaNotificationSender(NotificationChannel channel,
                                   NotificationService notificationService,
                                   ArNotificationProperties properties,
                                   NotificationPayloadMapper payloadMapper) {
        this.channel = channel;
        this.notificationService = notificationService;
        this.properties = properties;
        this.payloadMapper = payloadMapper;
    }

    @Override
    public NotificationChannel channel() {
        return channel;
    }

    @Override
    public void send(DunningNotification notification) {
        String email = channel == NotificationChannel.EMAIL ? notification.recipient() : null;
        String mobile = channel == NotificationChannel.SMS ? notification.recipient() : null;
        notificationService.enqueue(properties.dunningConfig(), email, mobile,
                NotificationSourceType.DUNNING, notification.invoiceNumber(),
                payloadMapper.dunning(dunningData(notification)));
    }

    private static Map<String, String> dunningData(DunningNotification n) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("debtorName", n.debtorName());
        data.put("invoiceNumber", n.invoiceNumber());
        data.put("currency", n.currency());
        data.put("amountOutstanding", n.amountOutstanding().toPlainString());
        data.put("dueDate", n.dueDate().toString());
        data.put("daysOverdue", Long.toString(n.daysOverdue()));
        return data;
    }
}
