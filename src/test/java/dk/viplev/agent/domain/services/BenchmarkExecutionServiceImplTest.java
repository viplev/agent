package dk.viplev.agent.domain.services;

import dk.viplev.agent.adapter.inbound.scheduling.MetricCollectorAdapter;
import dk.viplev.agent.domain.model.ContainerStartRequest;
import dk.viplev.agent.domain.model.RunContext;
import dk.viplev.agent.generated.model.BenchmarkRunStatusUpdateDTO;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BenchmarkExecutionServiceImplTest {

    @Mock
    private ViplevApiPort viplevApiPort;

    @Mock
    private MetricCollectorAdapter metricCollectorAdapter;

    @Mock
    private ContainerPort containerPort;

    private RunContext runContext;
    private BenchmarkExecutionServiceImpl service;

    private static final UUID BENCHMARK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        runContext = new RunContext();
        service = new BenchmarkExecutionServiceImpl(
                runContext,
                viplevApiPort,
                metricCollectorAdapter,
                containerPort,
                "grafana/k6:latest",
                "viplev_agent",
                0,
                new DirectExecutorService());
    }

    @Test
    void startRun_success_updatesStartedAndFinished() {
        when(metricCollectorAdapter.startCollection(BENCHMARK_ID, RUN_ID)).thenReturn(true);
        when(containerPort.startContainer(any(ContainerStartRequest.class))).thenReturn("k6-id");
        when(containerPort.isContainerRunning("k6-id")).thenReturn(true).thenReturn(false);
        when(containerPort.getContainerExitCode("k6-id")).thenReturn(0L);
        when(containerPort.getContainerLogs("k6-id", 500000)).thenReturn("{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":10,\"metric\":\"vus\",\"tags\":{}}}");

        service.startRun(BENCHMARK_ID, RUN_ID, "script");

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort, org.mockito.Mockito.times(2)).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues().get(0).getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.STARTED);
        assertThat(statusCaptor.getAllValues().get(1).getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.FINISHED);
        verify(metricCollectorAdapter).startCollection(BENCHMARK_ID, RUN_ID);
        verify(metricCollectorAdapter).stopCollection();
        verify(viplevApiPort).sendPerformanceMetrics(any(), any(), any());
        verify(containerPort).removeContainer("k6-id");
        assertThat(runContext.isActive()).isFalse();

        var requestCaptor = ArgumentCaptor.forClass(ContainerStartRequest.class);
        verify(containerPort).startContainer(requestCaptor.capture());
        var request = requestCaptor.getValue();
        assertThat(request.command().size()).isEqualTo(2);
        assertThat(request.command().get(0)).isEqualTo("-c");
        assertThat(request.command().get(1)).contains("k6 run --quiet --out json=/tmp/viplev-k6-output.json /tmp/viplev-k6-script.js && cat /tmp/viplev-k6-output.json");
        assertThat(request.volumes()).isEmpty();
        assertThat(request.env()).containsKey("VIPLEV_K6_SCRIPT_BASE64");
    }

    @Test
    void startRun_failure_updatesFailed() {
        when(metricCollectorAdapter.startCollection(BENCHMARK_ID, RUN_ID)).thenReturn(true);
        when(containerPort.startContainer(any(ContainerStartRequest.class))).thenReturn("k6-id");
        when(containerPort.isContainerRunning("k6-id")).thenReturn(false);
        when(containerPort.getContainerExitCode("k6-id")).thenReturn(137L);
        when(containerPort.getContainerLogs("k6-id", 4000)).thenReturn("k6 failed");

        service.startRun(BENCHMARK_ID, RUN_ID, "script");

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort, org.mockito.Mockito.times(2)).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues().get(0).getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.STARTED);
        assertThat(statusCaptor.getAllValues().get(1).getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED);
        assertThat(statusCaptor.getAllValues().get(1).getStatusReason()).contains("137");
        verify(containerPort).removeContainer("k6-id");
    }

    @Test
    void startRun_whenExitCodeMissing_updatesFailed() {
        when(metricCollectorAdapter.startCollection(BENCHMARK_ID, RUN_ID)).thenReturn(true);
        when(containerPort.startContainer(any(ContainerStartRequest.class))).thenReturn("k6-id");
        when(containerPort.isContainerRunning("k6-id")).thenReturn(false);
        when(containerPort.getContainerExitCode("k6-id")).thenReturn(null);
        when(containerPort.getContainerLogs("k6-id", 4000)).thenReturn("k6 logs");

        service.startRun(BENCHMARK_ID, RUN_ID, "script");

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort, org.mockito.Mockito.times(2)).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues().get(0).getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.STARTED);
        assertThat(statusCaptor.getAllValues().get(1).getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED);
        assertThat(statusCaptor.getAllValues().get(1).getStatusReason()).contains("no exit code");
    }

    @Test
    void stopRun_activeRun_stopsContainerAndUpdatesStatus() {
        runContext.activate(BENCHMARK_ID, RUN_ID);
        runContext.setK6ContainerId("k6-id");

        service.stopRun(BENCHMARK_ID, RUN_ID);

        verify(containerPort).stopContainer("k6-id");
        verify(metricCollectorAdapter).stopCollection();
        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.STOPPED);
        assertThat(runContext.isActive()).isFalse();
    }

    @Test
    void startRun_whenAlreadyActive_isIgnored() {
        runContext.activate(BENCHMARK_ID, RUN_ID);

        service.startRun(UUID.randomUUID(), UUID.randomUUID(), "script");

        verify(viplevApiPort, never()).updateRunStatus(any(), any(), any());
        verify(metricCollectorAdapter, never()).startCollection(any(), any());
        verify(containerPort, never()).startContainer(any());
    }

    @Test
    void stopRun_nonMatchingActiveRun_isIgnored() {
        runContext.activate(BENCHMARK_ID, RUN_ID);

        service.stopRun(UUID.randomUUID(), UUID.randomUUID());

        verify(viplevApiPort, never()).updateRunStatus(any(), any(), any());
        verify(containerPort, never()).stopContainer(any());
        assertThat(runContext.isActive()).isTrue();
    }

    @Test
    void startRun_blankInstructions_updatesFailed() {
        service.startRun(BENCHMARK_ID, RUN_ID, " ");

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED);
        assertThat(statusCaptor.getValue().getStatusReason()).contains("k6Instructions");
    }

    @Test
    void startRun_submitRejected_deactivatesRunAndUpdatesFailed() {
        service = new BenchmarkExecutionServiceImpl(
                runContext,
                viplevApiPort,
                metricCollectorAdapter,
                containerPort,
                "grafana/k6:latest",
                "viplev_agent",
                0,
                new RejectingExecutorService());

        service.startRun(BENCHMARK_ID, RUN_ID, "script");

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED);
        assertThat(statusCaptor.getValue().getStatusReason()).contains("Failed to schedule benchmark execution");
        assertThat(runContext.isActive()).isFalse();
        verify(metricCollectorAdapter, never()).startCollection(any(), any());
        verify(containerPort, never()).startContainer(any());
    }

    @Test
    void stopRun_whenContainerStopFails_updatesFailed() {
        runContext.activate(BENCHMARK_ID, RUN_ID);
        runContext.setK6ContainerId("k6-id");
        doThrow(new RuntimeException("docker stop failed")).when(containerPort).stopContainer("k6-id");

        service.stopRun(BENCHMARK_ID, RUN_ID);

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED);
        assertThat(statusCaptor.getValue().getStatusReason()).contains("Failed to stop benchmark run");
        verify(metricCollectorAdapter).stopCollection();
    }

    @Test
    void stopBeforeExecutorRuns_preventsStartTransition() {
        var queuedExecutor = new QueuedExecutorService();
        service = new BenchmarkExecutionServiceImpl(
                runContext,
                viplevApiPort,
                metricCollectorAdapter,
                containerPort,
                "grafana/k6:latest",
                "viplev_agent",
                0,
                queuedExecutor);

        service.startRun(BENCHMARK_ID, RUN_ID, "script");
        service.stopRun(BENCHMARK_ID, RUN_ID);
        queuedExecutor.runNext();

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.STOPPED);
        verify(metricCollectorAdapter, never()).startCollection(any(), any());
        verify(containerPort, never()).startContainer(any());
        assertThat(runContext.isActive()).isFalse();
    }

    @Test
    void startRun_timeout_updatesFailedWithExplicitReason() {
        service = new BenchmarkExecutionServiceImpl(
                runContext,
                viplevApiPort,
                metricCollectorAdapter,
                containerPort,
                new K6Service(viplevApiPort, "grafana/k6:latest", "viplev_agent"),
                0,
                1,
                200,
                200,
                200,
                new DirectExecutorService());

        when(metricCollectorAdapter.startCollection(BENCHMARK_ID, RUN_ID)).thenReturn(true);
        when(containerPort.startContainer(any(ContainerStartRequest.class))).thenReturn("k6-id");
        when(containerPort.isContainerRunning("k6-id")).thenReturn(true);
        when(containerPort.getContainerLogs("k6-id", 4000)).thenReturn("timeout logs");

        service.startRun(BENCHMARK_ID, RUN_ID, "script");

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort, org.mockito.Mockito.times(2)).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues().get(1).getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED);
        assertThat(statusCaptor.getAllValues().get(1).getStatusReason()).contains("timed out");
        verify(containerPort, atLeastOnce()).stopContainer("k6-id");
        verify(containerPort).removeContainer("k6-id");
    }

    @Test
    void startRun_nonZeroExit_statusReasonIsBounded() {
        service = new BenchmarkExecutionServiceImpl(
                runContext,
                viplevApiPort,
                metricCollectorAdapter,
                containerPort,
                new K6Service(viplevApiPort, "grafana/k6:latest", "viplev_agent"),
                0,
                300000,
                40,
                500,
                500,
                new DirectExecutorService());

        when(metricCollectorAdapter.startCollection(BENCHMARK_ID, RUN_ID)).thenReturn(true);
        when(containerPort.startContainer(any(ContainerStartRequest.class))).thenReturn("k6-id");
        when(containerPort.isContainerRunning("k6-id")).thenReturn(false);
        when(containerPort.getContainerExitCode("k6-id")).thenReturn(137L);
        when(containerPort.getContainerLogs("k6-id", 500)).thenReturn("x".repeat(500));

        service.startRun(BENCHMARK_ID, RUN_ID, "script");

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort, org.mockito.Mockito.times(2)).updateRunStatus(any(), any(), statusCaptor.capture());
        var reason = statusCaptor.getAllValues().get(1).getStatusReason();
        assertThat(reason.length()).isLessThanOrEqualTo(40);
        assertThat(reason).contains("truncated");
    }

    @Test
    void startRun_parseFailure_updatesFailedAndSkipsMetricSend() {
        service = new BenchmarkExecutionServiceImpl(
                runContext,
                viplevApiPort,
                metricCollectorAdapter,
                containerPort,
                new K6Service(viplevApiPort, "grafana/k6:latest", "viplev_agent"),
                0,
                300000,
                200,
                200,
                200,
                new DirectExecutorService());

        when(metricCollectorAdapter.startCollection(BENCHMARK_ID, RUN_ID)).thenReturn(true);
        when(containerPort.startContainer(any(ContainerStartRequest.class))).thenReturn("k6-id");
        when(containerPort.isContainerRunning("k6-id")).thenReturn(false);
        when(containerPort.getContainerExitCode("k6-id")).thenReturn(0L);
        when(containerPort.getContainerLogs("k6-id", 200)).thenReturn("no json here");

        service.startRun(BENCHMARK_ID, RUN_ID, "script");

        var statusCaptor = ArgumentCaptor.forClass(BenchmarkRunStatusUpdateDTO.class);
        verify(viplevApiPort, org.mockito.Mockito.times(2)).updateRunStatus(any(), any(), statusCaptor.capture());
        assertThat(statusCaptor.getAllValues().get(1).getStatus()).isEqualTo(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED);
        assertThat(statusCaptor.getAllValues().get(1).getStatusReason()).contains("No K6 metrics found");
        verify(viplevApiPort, never()).sendPerformanceMetrics(any(), any(), any());
    }

    private static final class DirectExecutorService extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }

    private static final class RejectingExecutorService extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            throw new RejectedExecutionException("executor is shutting down");
        }
    }

    private static final class QueuedExecutorService extends java.util.concurrent.AbstractExecutorService {
        private volatile boolean shutdown;
        private Runnable next;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, java.util.concurrent.TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            this.next = command;
        }

        void runNext() {
            if (next != null) {
                next.run();
                next = null;
            }
        }
    }
}
