package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArNotificationProperties;
import com.artivisi.accountreceivable.dto.NotificationRequest;
import com.artivisi.accountreceivable.service.notification.NotificationPayloadMapper;
import tools.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes a {@link NotificationRequest} to the hub's Kafka topic as a JSON string (the hub
 * consumes {@code String} then Jackson-deserializes), under the envelope names the deployment's
 * {@link NotificationPayloadMapper} gives. Sends synchronously so a broker failure
 * propagates to {@link NotificationService#publish} for retry.
 */
@Component
public class KafkaNotificationPublisher implements NotificationPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final ArNotificationProperties properties;
    private final NotificationPayloadMapper payloadMapper;

    public KafkaNotificationPublisher(KafkaTemplate<String, String> kafkaTemplate,
                                      ObjectMapper objectMapper,
                                      ArNotificationProperties properties,
                                      NotificationPayloadMapper payloadMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.payloadMapper = payloadMapper;
    }

    @Override
    public void publish(NotificationRequest request) {
        String json;
        try {
            json = objectMapper.writeValueAsString(payloadMapper.envelope(request));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize NotificationRequest", e);
        }
        try {
            kafkaTemplate.send(properties.topic(), json).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted publishing notification", e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish notification to topic " + properties.topic(), e);
        }
    }
}
