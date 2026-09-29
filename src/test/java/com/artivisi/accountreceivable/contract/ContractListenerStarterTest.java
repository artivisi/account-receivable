package com.artivisi.accountreceivable.contract;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The starter's own rules. {@link BrokerUnreachableStartupTest} proves AR boots without a broker;
 * this proves the retry that gets it consuming again afterwards, which that test cannot show without
 * a broker to come back.
 */
class ContractListenerStarterTest {

    private final KafkaListenerEndpointRegistry registry = mock(KafkaListenerEndpointRegistry.class);

    private ContractListenerStarter starterFor(MessageListenerContainer... containers) {
        doReturn(List.of(containers)).when(registry).getListenerContainers();
        return new ContractListenerStarter(registry);
    }

    @Test
    void startsAContainerThatIsNotRunning() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(false);

        starterFor(container).startListeners();

        verify(container).start();
    }

    @Test
    void leavesARunningContainerAlone() {
        // Without this the scheduled retry would restart a healthy consumer every 30 seconds,
        // rebalancing the group for no reason.
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(true);

        starterFor(container).startListeners();

        verify(container, never()).start();
    }

    @Test
    void aFailedStartIsCleanedUpAndTriedAgainLater() {
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        when(container.isRunning()).thenReturn(false);
        doThrow(new IllegalStateException("broker unreachable")).when(container).start();
        ContractListenerStarter starter = starterFor(container);

        // Must not propagate: this runs on the scheduler, and a thrown exception would cancel the
        // fixed-delay task — the retry would be silently gone and AR would never consume again.
        starter.startListeners();
        starter.startListeners();

        verify(container, times(2)).start();
        verify(container, times(2)).stop();
    }

    @Test
    void oneBadContainerDoesNotStopTheOthersFromStarting() {
        MessageListenerContainer broken = mock(MessageListenerContainer.class);
        when(broken.isRunning()).thenReturn(false);
        doThrow(new IllegalStateException("broker unreachable")).when(broken).start();
        MessageListenerContainer healthy = mock(MessageListenerContainer.class);
        when(healthy.isRunning()).thenReturn(false);

        starterFor(broken, healthy).startListeners();

        verify(healthy).start();
    }
}
