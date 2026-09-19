package com.artivisi.accountreceivable.config;

import com.artivisi.accountreceivable.service.notification.NotificationPayloadMapper;
import com.artivisi.accountreceivable.service.notification.PassthroughNotificationPayloadMapper;
import com.artivisi.accountreceivable.service.notification.TemplateNotificationPayloadMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Selects the names this deployment's notification hub expects. */
@Configuration
public class NotificationPayloadConfig {

    @Bean
    public NotificationPayloadMapper notificationPayloadMapper(ArNotificationProperties properties) {
        return switch (properties.payloadMapper()) {
            case PASSTHROUGH -> new PassthroughNotificationPayloadMapper();
            case TEMPLATE -> new TemplateNotificationPayloadMapper(properties.template());
        };
    }
}
