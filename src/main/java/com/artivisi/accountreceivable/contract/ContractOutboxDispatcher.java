package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.entity.ContractOutboxStatus;
import com.artivisi.accountreceivable.repository.ContractEventOutboxRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;

/**
 * Drains pending contract events to Kafka, oldest first, one transaction each via
 * {@link ContractOutboxPublisher}. Ordering within a debtor is preserved because rows are drained
 * in creation order and keyed on the debtor code, which pins them to one partition.
 */
@Component
@ConditionalOnProperty(prefix = "ar.contract", name = "mode", havingValue = "KAFKA")
public class ContractOutboxDispatcher {

    private static final int BATCH = 200;

    private final ContractEventOutboxRepository repository;
    private final ContractOutboxPublisher publisher;
    private final Clock clock;

    public ContractOutboxDispatcher(ContractEventOutboxRepository repository,
                                    ContractOutboxPublisher publisher, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ar.contract.poll-interval-ms}",
            initialDelayString = "${ar.contract.poll-interval-ms}")
    public void drain() {
        for (ContractEventOutbox row : repository.findByStatusAndNextAttemptAtLessThanEqualOrderByCreatedAtAsc(
                ContractOutboxStatus.PENDING, Instant.now(clock), Limit.of(BATCH))) {
            publisher.publish(row.getId());
        }
    }
}
