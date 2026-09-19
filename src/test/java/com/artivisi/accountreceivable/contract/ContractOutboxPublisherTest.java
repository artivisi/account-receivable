package com.artivisi.accountreceivable.contract;

import com.artivisi.accountreceivable.config.ArContractProperties;
import com.artivisi.accountreceivable.entity.ContractEventOutbox;
import com.artivisi.accountreceivable.entity.ContractOutboxStatus;
import com.artivisi.accountreceivable.repository.ContractEventOutboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The outcome of a send must be written back through the repository, not left on a detached
 * entity — the first dispatcher lost it and republished the same events on every poll.
 */
class ContractOutboxPublisherTest {

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final ContractEventOutboxRepository repository = mock(ContractEventOutboxRepository.class);
    private final ArContractProperties properties = new ArContractProperties(
            ArContractProperties.Mode.KAFKA, "ar", null, 12, 3, 30, 5000, "8888001");
    private final ContractOutboxPublisher publisher =
            new ContractOutboxPublisher(repository, kafka, properties, Clock.systemUTC());

    private ContractEventOutbox pending() {
        ContractEventOutbox row = new ContractEventOutbox();
        row.setTopic("invoice-event-v2");
        row.setMessageKey("2600000001");
        row.setEventType("invoice.issued");
        row.setPayload("{}");
        row.setStatus(ContractOutboxStatus.PENDING);
        row.setAttempts(0);
        row.setMaxAttempts(3);
        row.setNextAttemptAt(Instant.now());
        when(repository.findById("r1")).thenReturn(Optional.of(row));
        return row;
    }

    @Test
    void successfulSend_isSavedAsSent() {
        ContractEventOutbox row = pending();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish("r1");

        assertThat(row.getStatus()).isEqualTo(ContractOutboxStatus.SENT);
        verify(repository).save(row);
        verify(kafka).send("invoice-event-v2", "2600000001", "{}");
    }

    @Test
    void failedSend_isSavedWithBackoff_thenFailedAfterMaxAttempts() {
        ContractEventOutbox row = pending();
        when(kafka.send(anyString(), anyString(), anyString()))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("broker down")));

        publisher.publish("r1");
        assertThat(row.getStatus()).isEqualTo(ContractOutboxStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getLastError()).contains("broker down");
        assertThat(row.getNextAttemptAt()).isAfter(Instant.now().plusSeconds(20));

        publisher.publish("r1");
        publisher.publish("r1");
        assertThat(row.getStatus()).isEqualTo(ContractOutboxStatus.FAILED);
        verify(repository, org.mockito.Mockito.times(3)).save(any(ContractEventOutbox.class));
    }
}
