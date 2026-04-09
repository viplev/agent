package dk.viplev.agent.domain.services;

import dk.viplev.agent.adapter.inbound.scheduling.MetricCollectorAdapter;
import dk.viplev.agent.domain.exception.AgentException;
import dk.viplev.agent.domain.model.ContainerStartRequest;
import dk.viplev.agent.domain.model.RunContext;
import dk.viplev.agent.generated.model.BenchmarkRunStatusUpdateDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.port.inbound.BenchmarkExecutionUseCase;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Profile("docker")
public class BenchmarkExecutionServiceImpl implements BenchmarkExecutionUseCase {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkExecutionServiceImpl.class);
    private static final AtomicInteger RUNNER_THREAD_COUNTER = new AtomicInteger(0);

    private final RunContext runContext;
    private final ViplevApiPort viplevApiPort;
    private final MetricCollectorAdapter metricCollectorAdapter;
    private final ContainerPort containerPort;
    private final String k6Image;
    private final String k6Network;
    private final long completionPollIntervalMs;
    private final ExecutorService runExecutor;

    public BenchmarkExecutionServiceImpl(RunContext runContext,
                                         ViplevApiPort viplevApiPort,
                                         MetricCollectorAdapter metricCollectorAdapter,
                                         ContainerPort containerPort,
                                         @Value("${agent.k6-image}") String k6Image,
                                         @Value("${agent.k6-network}") String k6Network,
                                         @Value("${agent.k6-completion-poll-interval-ms:1000}") long completionPollIntervalMs) {
        this(runContext, viplevApiPort, metricCollectorAdapter, containerPort,
                k6Image, k6Network, completionPollIntervalMs,
                Executors.newSingleThreadExecutor(daemonThreadFactory()));
    }

    BenchmarkExecutionServiceImpl(RunContext runContext,
                                  ViplevApiPort viplevApiPort,
                                  MetricCollectorAdapter metricCollectorAdapter,
                                  ContainerPort containerPort,
                                  String k6Image,
                                  String k6Network,
                                  long completionPollIntervalMs,
                                  ExecutorService runExecutor) {
        this.runContext = runContext;
        this.viplevApiPort = viplevApiPort;
        this.metricCollectorAdapter = metricCollectorAdapter;
        this.containerPort = containerPort;
        this.k6Image = k6Image;
        this.k6Network = k6Network;
        this.completionPollIntervalMs = completionPollIntervalMs;
        this.runExecutor = runExecutor;
    }

    @Override
    public void startRun(UUID benchmarkId, UUID runId, String k6Instructions) {
        if (benchmarkId == null || runId == null) {
            throw new AgentException("benchmarkId and runId are required for startRun");
        }

        if (!runContext.activate(benchmarkId, runId)) {
            var activeRun = runContext.getCurrentRun();
            log.warn("Ignoring PENDING_START for benchmark={} run={} because active run is {}",
                    benchmarkId, runId, activeRun.orElse(null));
            return;
        }

        try {
            runExecutor.submit(() -> executeRun(benchmarkId, runId, k6Instructions));
        } catch (RejectedExecutionException e) {
            log.error("Failed to submit benchmark execution for benchmark={} run={}", benchmarkId, runId, e);
            if (runContext.deactivateIfMatch(benchmarkId, runId).isPresent()) {
                viplevApiPort.updateRunStatus(benchmarkId, runId,
                        statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED,
                                "Failed to schedule benchmark execution: " + e.getMessage()));
            }
        }
    }

    private void executeRun(UUID benchmarkId, UUID runId, String k6Instructions) {

        String k6ContainerId = null;
        boolean metricsStarted = false;
        boolean metricsStopped = false;
        try {
            if (!runContext.isActive(benchmarkId, runId)) {
                return;
            }

            if (k6Instructions == null || k6Instructions.isBlank()) {
                throw new AgentException("k6Instructions are required for startRun");
            }

            if (!runContext.isActive(benchmarkId, runId)) {
                return;
            }

            viplevApiPort.updateRunStatus(benchmarkId, runId, statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.STARTED, null));

            if (!runContext.isActive(benchmarkId, runId)) {
                return;
            }

            metricsStarted = metricCollectorAdapter.startCollection(benchmarkId, runId);
            if (!metricsStarted) {
                throw new AgentException("Failed to start metric collection");
            }

            if (!runContext.isActive(benchmarkId, runId)) {
                return;
            }

            k6ContainerId = containerPort.startContainer(k6StartRequest(k6Instructions));
            if (runContext.setK6ContainerId(k6ContainerId).isEmpty()) {
                return;
            }

            boolean completedNaturally = waitForContainerToComplete(benchmarkId, runId, k6ContainerId);
            if (!completedNaturally) {
                return;
            }

            if (!runContext.isActive(benchmarkId, runId)) {
                return;
            }

            stopMetricsSilently();
            metricsStopped = true;
            sendEmptyPerformanceMetrics(benchmarkId, runId);
            viplevApiPort.updateRunStatus(benchmarkId, runId, statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.FINISHED, null));
        } catch (Exception e) {
            failRun(benchmarkId, runId, k6ContainerId, metricsStarted, e);
        } finally {
            if (!runContext.isActive(benchmarkId, runId)) {
                cleanupCancelledRun(k6ContainerId, metricsStarted, metricsStopped);
            }
            runContext.deactivateIfMatch(benchmarkId, runId);
        }
    }

    @PreDestroy
    void shutdown() {
        runExecutor.shutdownNow();
    }

    @Override
    public void stopRun(UUID benchmarkId, UUID runId) {
        var runToStop = runContext.deactivateIfMatch(benchmarkId, runId);
        if (runToStop.isEmpty()) {
            var currentRun = runContext.getCurrentRun();
            if (currentRun.isPresent()) {
                var run = currentRun.get();
                log.warn("Ignoring PENDING_STOP for benchmark={} run={} because active run is benchmark={} run={}",
                        benchmarkId, runId, run.benchmarkId(), run.runId());
            } else {
                log.warn("Ignoring PENDING_STOP for benchmark={} run={} because no run is active", benchmarkId, runId);
            }
            return;
        }

        var run = runToStop.get();
        String stopFailureReason = null;

        try {
            if (run.k6ContainerId() != null) {
                containerPort.stopContainer(run.k6ContainerId());
            }
        } catch (Exception e) {
            stopFailureReason = e.getMessage() == null ? "Failed to stop K6 container" : e.getMessage();
            log.warn("Failed to stop K6 container for benchmark={} run={}", benchmarkId, runId, e);
        } finally {
            stopMetricsSilently();
            if (stopFailureReason == null) {
                viplevApiPort.updateRunStatus(benchmarkId, runId,
                        statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.STOPPED, null));
            } else {
                viplevApiPort.updateRunStatus(benchmarkId, runId,
                        statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED,
                                "Failed to stop benchmark run: " + stopFailureReason));
            }
        }
    }

    private ContainerStartRequest k6StartRequest(String k6Instructions) {
        return new ContainerStartRequest(
                k6Image,
                Map.of("K6_SCRIPT", k6Instructions),
                Map.of(),
                k6Network,
                List.of("run", "-"));
    }

    private boolean waitForContainerToComplete(UUID benchmarkId, UUID runId, String containerId) {
        while (true) {
            if (!runContext.isActive(benchmarkId, runId)) {
                return false;
            }

            boolean isRunning = containerPort.isContainerRunning(containerId);
            if (isRunning) {
                sleep(completionPollIntervalMs);
                continue;
            }

            Long exitCode = containerPort.getContainerExitCode(containerId);
            if (exitCode != null && exitCode != 0L) {
                throw new AgentException("K6 container exited with code " + exitCode);
            }
            return true;
        }
    }

    private void failRun(UUID benchmarkId,
                         UUID runId,
                         String k6ContainerId,
                         boolean metricsStarted,
                         Exception exception) {
        try {
            if (k6ContainerId != null) {
                containerPort.stopContainer(k6ContainerId);
            }
        } catch (Exception stopException) {
            log.warn("Failed to stop k6 container during failure handling", stopException);
        }

        if (metricsStarted) {
            stopMetricsSilently();
        }

        if (!runContext.isActive(benchmarkId, runId)) {
            return;
        }

        String reason = exception.getMessage() == null ? "Unknown run failure" : exception.getMessage();
        viplevApiPort.updateRunStatus(benchmarkId, runId,
                statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED, reason));
    }

    private void stopMetricsSilently() {
        try {
            metricCollectorAdapter.stopCollection();
        } catch (Exception e) {
            log.warn("Failed to stop metric collection cleanly", e);
        }
    }

    private void stopContainerSilently(String containerId) {
        if (containerId == null) {
            return;
        }

        try {
            containerPort.stopContainer(containerId);
        } catch (Exception e) {
            log.warn("Failed to stop K6 container {} during cleanup", containerId, e);
        }
    }

    private void cleanupCancelledRun(String k6ContainerId, boolean metricsStarted, boolean metricsStopped) {
        stopContainerSilently(k6ContainerId);
        if (metricsStarted && !metricsStopped) {
            stopMetricsSilently();
        }
    }

    private void sendEmptyPerformanceMetrics(UUID benchmarkId, UUID runId) {
        viplevApiPort.sendPerformanceMetrics(benchmarkId, runId,
                new MetricPerformanceDTO().httpMetrics(List.of()).vusMetrics(List.of()));
    }

    private BenchmarkRunStatusUpdateDTO statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum status, String reason) {
        return new BenchmarkRunStatusUpdateDTO().status(status).statusReason(reason);
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted while waiting for k6 container completion", e);
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable,
                    "benchmark-execution-" + RUNNER_THREAD_COUNTER.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
