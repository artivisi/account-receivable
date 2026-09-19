package com.artivisi.accountreceivable.config;

import com.artivisi.accountreceivable.entity.NotificationChannel;
import com.artivisi.accountreceivable.service.KafkaNotificationSender;
import com.artivisi.accountreceivable.service.NotificationService;
import com.artivisi.accountreceivable.service.notification.NotificationPayloadMapper;
import com.artivisi.accountreceivable.spi.NotificationSender;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the outbox-backed {@link NotificationSender} beans consumed by dunning. EMAIL is always
 * on; SMS is gated by {@code ar.notification.sms-enabled} (cost control) so a deployment with SMS off
 * has no SMS sender — a dunning run for SMS then fails loud, as before.
 */
@Configuration
public class NotificationSenderConfig {

    @Bean
    public NotificationSender emailNotificationSender(NotificationService notificationService,
                                                      ArNotificationProperties properties,
                                                      NotificationPayloadMapper payloadMapper) {
        return new KafkaNotificationSender(NotificationChannel.EMAIL, notificationService, properties,
                payloadMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "ar.notification", name = "sms-enabled", havingValue = "true")
    public NotificationSender smsNotificationSender(NotificationService notificationService,
                                                    ArNotificationProperties properties,
                                                    NotificationPayloadMapper payloadMapper) {
        return new KafkaNotificationSender(NotificationChannel.SMS, notificationService, properties,
                payloadMapper);
    }
}
