package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.config.ArContractProperties;
import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.entity.ContractOutboxStatus;
import com.artivisi.accountreceivable.repository.ContractEventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * Publishes one outbox row and records the outcome, in its own transaction. A separate bean from
 * the poller on purpose: a method calling itself bypasses the transaction proxy, and the first
 * version of the dispatcher did exactly that — the send succeeded, the status change was never
 * flushed, and the same events went out again on every poll.
 */
@Service
public class ContractOutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(ContractOutboxPublisher.class);
    private static final int ERROR_MAX = 512;

    private final ContractEventOutboxRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ArContractProperties properties;
    private final Clock clock;

    public ContractOutboxPublisher(ContractEventOutboxRepository repository,
                                   KafkaTemplate<String, String> kafkaTemplate,
                                   ArContractProperties properties, Clock clock) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void publish(String id) {
        ContractEventOutbox row = repository.findById(id).orElse(null);
        if (row == null || row.getStatus() != ContractOutboxStatus.PENDING) {
            return;
        }
        try {
            kafkaTemplate.send(row.getTopic(), row.getMessageKey(), row.getPayload()).get();
            row.setStatus(ContractOutboxStatus.SENT);
            row.setLastError(null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(row, "interrupted");
        } catch (Exception e) {
            fail(row, e.toString());
        }
        repository.save(row);
    }

    private void fail(ContractEventOutbox row, String error) {
        int attempts = row.getAttempts() + 1;
        row.setAttempts(attempts);
        row.setLastError(error.substring(0, Math.min(error.length(), ERROR_MAX)));
        if (attempts >= row.getMaxAttempts()) {
            row.setStatus(ContractOutboxStatus.FAILED);
            log.error("Contract event {} ({}) FAILED after {} attempts: {}", row.getId(), row.getEventType(), attempts, error);
        } else {
            long backoff = properties.backoffBaseSeconds() * (1L << (attempts - 1));
            row.setNextAttemptAt(Instant.now(clock).plusSeconds(backoff));
            log.warn("Contract event {} attempt {} failed, retrying in {}s", row.getId(), attempts, backoff);
        }
    }
}
