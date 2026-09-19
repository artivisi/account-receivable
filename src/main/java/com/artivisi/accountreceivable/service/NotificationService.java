package com.artivisi.accountreceivable.service;

import com.artivisi.accountreceivable.config.ArNotificationProperties;
import com.artivisi.accountreceivable.dto.NotificationRequest;
import com.artivisi.accountreceivable.entity.NotificationOutbox;
import com.artivisi.accountreceivable.entity.NotificationOutboxStatus;
import com.artivisi.accountreceivable.entity.NotificationSourceType;
import com.artivisi.accountreceivable.repository.NotificationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Notification outbox writer + processor. {@link #enqueue} runs in the business transaction
 * (MANDATORY) so a notification is never lost between commit and publish; {@link #publish} sends each
 * row in its own transaction with backoff, marking SENT on success or terminal FAILED (surfaced,
 * never dropped). Mirrors {@link ChargeCancellationService}.
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final int BATCH_SIZE = 50;
    private static final int ERROR_MAX = 500;

    private final NotificationOutboxRepository outboxRepository;
    private final NotificationPublisher publisher;
    private final AuditService auditService;
    private final ArNotificationProperties properties;
    private final Clock clock;

    public NotificationService(NotificationOutboxRepository outboxRepository,
                               NotificationPublisher publisher,
                               AuditService auditService,
                               ArNotificationProperties properties,
                               Clock clock) {
        this.outboxRepository = outboxRepository;
        this.publisher = publisher;
        this.auditService = auditService;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Enqueue a PENDING notification, joining the caller's business transaction. SMS gating: when
     * {@code sms-enabled=false} the {@code mobile} recipient is dropped (email may still fire). If no
     * channel remains (both recipients absent), nothing is enqueued — logged loud + audited, never
     * silent.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String configId, String email, String mobile,
                        NotificationSourceType sourceType, String sourceId, Map<String, String> data) {
        String gatedMobile = properties.smsEnabled() ? mobile : null;
        if (isBlank(email) && isBlank(gatedMobile)) {
            log.warn("No contact for {} {} (config {}); skipping notification", sourceType, sourceId, configId);
            auditService.record("NOTIFICATION_SKIPPED", sourceType.name(), sourceId,
                    "no email/mobile recipient for config " + configId);
            return;
        }
        NotificationOutbox row = new NotificationOutbox();
        row.setConfigId(configId);
        row.setRecipientEmail(isBlank(email) ? null : email);
        row.setRecipientMobile(isBlank(gatedMobile) ? null : gatedMobile);
        row.setData(data);
        row.setSourceType(sourceType);
        row.setSourceId(sourceId);
        row.setStatus(NotificationOutboxStatus.PENDING);
        row.setAttempts(0);
        row.setMaxAttempts(properties.maxAttempts());
        row.setNextAttemptAt(Instant.now(clock));
        outboxRepository.save(row);
    }

    @Transactional(readOnly = true)
    public List<NotificationOutbox> findDue() {
        return outboxRepository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                NotificationOutboxStatus.PENDING, Instant.now(clock), Limit.of(BATCH_SIZE));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(String id) {
        NotificationOutbox row = outboxRepository.findById(id).orElse(null);
        if (row == null || row.getStatus() != NotificationOutboxStatus.PENDING) {
            return;
        }
        try {
            publisher.publish(new NotificationRequest(
                    row.getConfigId(), row.getRecipientEmail(), row.getRecipientMobile(), row.getData()));
            row.setStatus(NotificationOutboxStatus.SENT);
            row.setLastError(null);
        } catch (Exception e) {
            recordFailure(row, e.getMessage());
        }
    }

    private void recordFailure(NotificationOutbox row, String error) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastError(error == null ? null : error.substring(0, Math.min(error.length(), ERROR_MAX)));
        if (attempts >= row.getMaxAttempts()) {
            row.setStatus(NotificationOutboxStatus.FAILED);
            log.error("Notification {} FAILED after {} attempts: {}", row.getId(), attempts, error);
            auditService.record("NOTIFICATION_FAILED", row.getSourceType().name(), row.getSourceId(),
                    "config " + row.getConfigId() + " failed after " + attempts + " attempts: " + error);
        } else {
            long backoff = properties.backoffBaseSeconds() * (1L << (attempts - 1));
            row.setNextAttemptAt(Instant.now(clock).plusSeconds(backoff));
            log.warn("Notification {} attempt {} failed, retrying in {}s", row.getId(), attempts, backoff);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
