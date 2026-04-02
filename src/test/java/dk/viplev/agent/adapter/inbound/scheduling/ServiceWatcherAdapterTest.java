package dk.viplev.agent.adapter.inbound.scheduling;

import dk.viplev.agent.domain.model.ContainerEvent;
import dk.viplev.agent.port.inbound.ServiceDiscoveryUseCase;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ServiceWatcherAdapterTest {

    private static final long TEST_DEBOUNCE_MS = 200;

    @Mock
    private ServiceDiscoveryUseCase serviceDiscoveryUseCase;

    @Mock
    private ContainerPort containerPort;

    private ServiceWatcherAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ServiceWatcherAdapter(serviceDiscoveryUseCase, containerPort, TEST_DEBOUNCE_MS);
    }

    @AfterEach
    void tearDown() {
        adapter.shutdown();
    }

    @Test
    void onApplicationReady_performsInitialSync() {
        adapter.onApplicationReady();

        verify(serviceDiscoveryUseCase, timeout(TEST_DEBOUNCE_MS * 3)).syncServices();
        verify(containerPort).watchContainerEvents(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void containerEvent_triggersDebouncedSync() {
        adapter.onApplicationReady();

        var callbackCaptor = captureEventCallback();
        callbackCaptor.getValue().accept(containerEvent("start-1", ContainerEvent.EventType.STARTED));

        // Initial sync + debounced sync = 2 calls. Use Mockito timeout for async.
        verify(serviceDiscoveryUseCase, timeout(TEST_DEBOUNCE_MS * 3).times(2)).syncServices();
    }

    @Test
    void rapidEvents_debouncedToSingleSync() {
        adapter.onApplicationReady();

        var callbackCaptor = captureEventCallback();
        Consumer<ContainerEvent> callback = callbackCaptor.getValue();

        // Fire 5 events in rapid succession
        for (int i = 0; i < 5; i++) {
            callback.accept(containerEvent("container-" + i, ContainerEvent.EventType.STARTED));
        }

        // Wait for debounce to settle, then verify: 1 initial + 1 debounced = 2
        verify(serviceDiscoveryUseCase, timeout(TEST_DEBOUNCE_MS * 3).times(2)).syncServices();

        // Verify it wasn't called more than 2 times (no extra syncs after settling)
        try {
            Thread.sleep(TEST_DEBOUNCE_MS * 2);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        verify(serviceDiscoveryUseCase, times(2)).syncServices();
    }

    @Test
    void initialSyncFailure_doesNotPreventEventWatching() {
        doThrow(new RuntimeException("API unavailable"))
                .doNothing()
                .when(serviceDiscoveryUseCase).syncServices();

        adapter.onApplicationReady();

        // Even though initial sync failed, event watching should still be registered
        verify(containerPort).watchContainerEvents(org.mockito.ArgumentMatchers.any());

        // And a subsequent event-triggered sync should succeed
        var callbackCaptor = captureEventCallback();
        callbackCaptor.getValue().accept(containerEvent("new-container", ContainerEvent.EventType.STARTED));

        verify(serviceDiscoveryUseCase, timeout(TEST_DEBOUNCE_MS * 3).atLeast(2)).syncServices();
    }

    private ArgumentCaptor<Consumer<ContainerEvent>> captureEventCallback() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<ContainerEvent>> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(containerPort, atLeast(1)).watchContainerEvents(captor.capture());
        return captor;
    }

    private static ContainerEvent containerEvent(String name, ContainerEvent.EventType type) {
        return new ContainerEvent("id-" + name, name, type, Instant.now());
    }
}
