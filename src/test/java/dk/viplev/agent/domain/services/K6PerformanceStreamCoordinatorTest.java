package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.exception.AgentException;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.Closeable;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class K6PerformanceStreamCoordinatorTest {

    private static final UUID BENCHMARK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String VUS_POINT =
            "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":10,\"metric\":\"vus\",\"tags\":{}},\"metric\":\"vus\"}";

    @Mock
    private ViplevApiPort viplevApiPort;

    @Mock
    private ContainerPort containerPort;

    private final AtomicReference<Consumer<String>> onLine = new AtomicReference<>();
    private final AtomicReference<Consumer<Throwable>> onError = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(containerPort.followContainerLogs(anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    onLine.set(invocation.getArgument(1));
                    onError.set(invocation.getArgument(2));
                    return (Closeable) () -> {
                    };
                });
    }

    @Test
    void finishAndAwait_interruptsSleepAndFlushesWithinTimeout() {
        K6PerformanceStreamCoordinator coordinator = newCoordinator(10_000, 3, 1, 50);
        coordinator.start();

        onLine.get().accept(VUS_POINT);
        coordinator.finishAndAwait();

        verify(viplevApiPort, timeout(1000).times(1))
                .sendPerformanceMetrics(eq(BENCHMARK_ID), eq(RUN_ID), any());
    }

    @Test
    void sendBatchWithRetry_retriesTransientFailuresThenSucceeds() {
        doThrow(new RuntimeException("temporary failure #1"))
                .doThrow(new RuntimeException("temporary failure #2"))
                .doNothing()
                .when(viplevApiPort)
                .sendPerformanceMetrics(eq(BENCHMARK_ID), eq(RUN_ID), any());

        K6PerformanceStreamCoordinator coordinator = newCoordinator(5, 3, 1, 1000);
        coordinator.start();

        onLine.get().accept(VUS_POINT);
        verify(viplevApiPort, timeout(1500).times(3))
                .sendPerformanceMetrics(eq(BENCHMARK_ID), eq(RUN_ID), any());

        coordinator.finishAndAwait();
        assertThat(coordinator.getFatalError()).isNull();
    }

    @Test
    void sendBatchWithRetry_whenRetriesExhausted_setsFatalError() {
        doThrow(new RuntimeException("viplev unavailable"))
                .when(viplevApiPort)
                .sendPerformanceMetrics(eq(BENCHMARK_ID), eq(RUN_ID), any());

        K6PerformanceStreamCoordinator coordinator = newCoordinator(5, 2, 1, 1000);
        coordinator.start();

        onLine.get().accept(VUS_POINT);
        waitUntil(() -> coordinator.getFatalError() != null, 1500);

        assertThat(coordinator.getFatalError()).contains("after 2 attempts");
        verify(viplevApiPort, timeout(1000).times(2))
                .sendPerformanceMetrics(eq(BENCHMARK_ID), eq(RUN_ID), any());

        assertThatThrownBy(coordinator::finishAndAwait)
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("after 2 attempts");
    }

    @Test
    void onLogError_setsFatalError() {
        K6PerformanceStreamCoordinator coordinator = newCoordinator(1000, 3, 1, 1000);
        coordinator.start();

        onError.get().accept(new RuntimeException("docker stream broke"));
        waitUntil(() -> coordinator.getFatalError() != null, 1000);

        assertThat(coordinator.getFatalError()).contains("K6 log streaming failed");
        assertThatThrownBy(coordinator::finishAndAwait)
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("K6 log streaming failed");
    }

    private K6PerformanceStreamCoordinator newCoordinator(long flushIntervalMs,
                                                           int sendMaxRetries,
                                                           long sendBackoffMs,
                                                           long finalFlushTimeoutMs) {
        return new K6PerformanceStreamCoordinator(
                viplevApiPort,
                containerPort,
                BENCHMARK_ID,
                RUN_ID,
                "k6-id",
                flushIntervalMs,
                2000,
                20_000,
                sendMaxRetries,
                sendBackoffMs,
                finalFlushTimeoutMs
        );
    }

    private void waitUntil(BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting", e);
            }
        }
        throw new AssertionError("Condition was not met within " + timeoutMs + " ms");
    }
}
