package dk.viplev.agent.domain.services;

import dk.viplev.agent.adapter.inbound.scheduling.MetricCollectorAdapter;
import dk.viplev.agent.domain.exception.AgentException;
import dk.viplev.agent.domain.model.BenchmarkRunStatus;
import dk.viplev.agent.domain.model.RunContext;
import dk.viplev.agent.generated.model.BenchmarkRunStatusUpdateDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.port.inbound.BenchmarkExecutionUseCase;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
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
    private final K6Service k6Service;
    private final long completionPollIntervalMs;
    private final long k6TimeoutMs;
    private final int statusReasonMaxLength;
    private final int containerLogMaxBytes;
    private final int k6OutputLogMaxBytes;
    private final ExecutorService runExecutor;

    @Autowired
    public BenchmarkExecutionServiceImpl(RunContext runContext,
                                         ViplevApiPort viplevApiPort,
                                         MetricCollectorAdapter metricCollectorAdapter,
                                         ContainerPort containerPort,
                                         K6Service k6Service,
                                         @Value("${agent.k6-completion-poll-interval-ms:1000}") long completionPollIntervalMs,
                                         @Value("${agent.k6-timeout-ms:300000}") long k6TimeoutMs,
                                         @Value("${agent.run-status-reason-max-length:2000}") int statusReasonMaxLength,
                                         @Value("${agent.k6-log-max-bytes:4000}") int containerLogMaxBytes,
                                         @Value("${agent.k6-output-log-max-bytes:500000}") int k6OutputLogMaxBytes) {
        this(runContext, viplevApiPort, metricCollectorAdapter, containerPort, k6Service,
                completionPollIntervalMs, k6TimeoutMs, statusReasonMaxLength, containerLogMaxBytes,
                k6OutputLogMaxBytes,
                Executors.newSingleThreadExecutor(daemonThreadFactory()));
    }

    BenchmarkExecutionServiceImpl(RunContext runContext,
                                  ViplevApiPort viplevApiPort,
                                  MetricCollectorAdapter metricCollectorAdapter,
                                  ContainerPort containerPort,
                                  K6Service k6Service,
                                  long completionPollIntervalMs,
                                  long k6TimeoutMs,
                                  int statusReasonMaxLength,
                                  int containerLogMaxBytes,
                                  int k6OutputLogMaxBytes,
                                  ExecutorService runExecutor) {
        this.runContext = runContext;
        this.viplevApiPort = viplevApiPort;
        this.metricCollectorAdapter = metricCollectorAdapter;
        this.containerPort = containerPort;
        this.k6Service = k6Service;
        this.completionPollIntervalMs = completionPollIntervalMs;
        this.k6TimeoutMs = k6TimeoutMs;
        this.statusReasonMaxLength = statusReasonMaxLength;
        this.containerLogMaxBytes = containerLogMaxBytes;
        this.k6OutputLogMaxBytes = k6OutputLogMaxBytes;
        this.runExecutor = runExecutor;
    }

    BenchmarkExecutionServiceImpl(RunContext runContext,
                                  ViplevApiPort viplevApiPort,
                                  MetricCollectorAdapter metricCollectorAdapter,
                                  ContainerPort containerPort,
                                  String k6Image,
                                  String k6Network,
                                  long completionPollIntervalMs,
                                  ExecutorService runExecutor) {
        this(runContext,
                viplevApiPort,
                metricCollectorAdapter,
                containerPort,
                new K6Service(viplevApiPort, k6Image, k6Network),
                completionPollIntervalMs,
                300000,
                2000,
                4000,
                500000,
                runExecutor);
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

        if (!runContext.markStatusIfMatch(benchmarkId, runId, BenchmarkRunStatus.PENDING_START)) {
            runContext.deactivateIfMatch(benchmarkId, runId);
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
        boolean startedStatusSent = false;
        boolean runStopped = false;

        try {
            if (!runContext.isActive(benchmarkId, runId)) {
                return;
            }

            if (k6Instructions == null || k6Instructions.isBlank()) {
                throw new AgentException("k6Instructions are required for startRun");
            }

            if (!runContext.markStatusIfMatch(benchmarkId, runId, BenchmarkRunStatus.STARTED)) {
                return;
            }

            viplevApiPort.updateRunStatus(benchmarkId, runId,
                    statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.STARTED, null));
            startedStatusSent = true;

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

            k6ContainerId = startK6Container(k6Instructions);
            if (runContext.setK6ContainerIdIfMatchAndNotStopped(benchmarkId, runId, k6ContainerId).isEmpty()) {
                return;
            }

            waitForContainerToComplete(benchmarkId, runId, k6ContainerId);

            if (!runContext.isActive(benchmarkId, runId)) {
                return;
            }

            stopMetricsSilently();
            metricsStopped = true;

            String k6OutputLogs = containerPort.getContainerLogs(k6ContainerId, k6OutputLogMaxBytes);
            MetricPerformanceDTO metrics = k6Service.parsePerformanceMetricsFromLogs(k6OutputLogs);
            k6Service.sendPerformanceMetrics(benchmarkId, runId, metrics);

            if (!runContext.markStatusIfMatch(benchmarkId, runId, BenchmarkRunStatus.FINISHED)) {
                return;
            }

            viplevApiPort.updateRunStatus(benchmarkId, runId,
                    statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.FINISHED, null));
        } catch (Exception e) {
            if (runContext.isStopRequested(benchmarkId, runId)) {
                runStopped = true;
                return;
            }
            failRun(benchmarkId, runId, k6ContainerId, metricsStarted, metricsStopped, startedStatusSent, e);
        } finally {
            cleanup(k6ContainerId, metricsStarted, metricsStopped, runStopped);
            runContext.deactivateIfMatch(benchmarkId, runId);
        }
    }

    @PreDestroy
    void shutdown() {
        runExecutor.shutdownNow();
    }

    @Override
    public void stopRun(UUID benchmarkId, UUID runId) {
        var runToStop = runContext.getCurrentRun();
        if (runToStop.isEmpty()) {
            log.warn("Ignoring PENDING_STOP for benchmark={} run={} because no run is active", benchmarkId, runId);
            return;
        }

        var run = runToStop.get();
        if (!run.benchmarkId().equals(benchmarkId) || !run.runId().equals(runId)) {
            log.warn("Ignoring PENDING_STOP for benchmark={} run={} because active run is benchmark={} run={}",
                    benchmarkId, runId, run.benchmarkId(), run.runId());
            return;
        }

        if (!runContext.markStatusIfMatch(benchmarkId, runId, BenchmarkRunStatus.PENDING_STOP)) {
            return;
        }

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
                runContext.markStatusIfMatch(benchmarkId, runId, BenchmarkRunStatus.STOPPED);
                viplevApiPort.updateRunStatus(benchmarkId, runId,
                        statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.STOPPED, null));
            } else {
                if (runContext.markStatusIfMatch(benchmarkId, runId, BenchmarkRunStatus.FAILED)) {
                    viplevApiPort.updateRunStatus(benchmarkId, runId,
                            statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED,
                                    boundedReason("Failed to stop benchmark run: " + stopFailureReason)));
                }
            }
            runContext.deactivateIfMatch(benchmarkId, runId);
        }
    }

    private String startK6Container(String k6Instructions) {
        return containerPort.startContainer(k6Service.startRequest(k6Instructions));
    }

    private void waitForContainerToComplete(UUID benchmarkId, UUID runId, String containerId) {
        Instant startedAt = Instant.now();

        while (true) {
            if (runContext.isStopRequested(benchmarkId, runId)) {
                throw new StopRequestedException();
            }

            if (!runContext.isActive(benchmarkId, runId)) {
                return;
            }

            if (Duration.between(startedAt, Instant.now()).toMillis() > k6TimeoutMs) {
                throw new AgentException("K6 execution timed out after " + k6TimeoutMs + " ms");
            }

            boolean isRunning = containerPort.isContainerRunning(containerId);
            if (isRunning) {
                sleep(completionPollIntervalMs);
                continue;
            }

            Long exitCode = containerPort.getContainerExitCode(containerId);
            if (exitCode == null) {
                throw new AgentException("K6 container exited but no exit code was available");
            }

            if (exitCode != 0L) {
                throw new AgentException("K6 container exited with code " + exitCode);
            }
            return;
        }
    }

    private void failRun(UUID benchmarkId,
                         UUID runId,
                         String k6ContainerId,
                         boolean metricsStarted,
                         boolean metricsStopped,
                         boolean startedStatusSent,
                         Exception exception) {
        try {
            if (k6ContainerId != null) {
                containerPort.stopContainer(k6ContainerId);
            }
        } catch (Exception stopException) {
            log.warn("Failed to stop k6 container during failure handling", stopException);
        }

        if (metricsStarted && !metricsStopped) {
            stopMetricsSilently();
        }

        if (!runContext.isActive(benchmarkId, runId)) {
            return;
        }

        if (!runContext.markStatusIfMatch(benchmarkId, runId, BenchmarkRunStatus.FAILED)) {
            return;
        }

        String reason = exception.getMessage() == null ? "Unknown run failure" : exception.getMessage();
        if (startedStatusSent) {
            reason = reason + logsSuffix(k6ContainerId);
        }
        log.warn("Benchmark run failed for benchmark={} run={}: {}", benchmarkId, runId, reason, exception);
        viplevApiPort.updateRunStatus(benchmarkId, runId,
                statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum.FAILED, boundedReason(reason)));
    }

    private void cleanup(String k6ContainerId,
                         boolean metricsStarted,
                         boolean metricsStopped,
                         boolean runStopped) {
        if (k6ContainerId != null) {
            stopContainerSilently(k6ContainerId);
            removeContainerSilently(k6ContainerId);
        }

        if (metricsStarted && !metricsStopped) {
            stopMetricsSilently();
        }

        if (runStopped) {
            log.info("Run was stopped before completion");
        }
    }

    private void stopMetricsSilently() {
        try {
            metricCollectorAdapter.stopCollection();
        } catch (Exception e) {
            log.warn("Failed to stop metric collection cleanly", e);
        }
    }

    private void stopContainerSilently(String containerId) {
        try {
            containerPort.stopContainer(containerId);
        } catch (Exception e) {
            log.warn("Failed to stop K6 container {} during cleanup", containerId, e);
        }
    }

    private void removeContainerSilently(String containerId) {
        try {
            containerPort.removeContainer(containerId);
        } catch (Exception e) {
            log.warn("Failed to remove K6 container {} during cleanup", containerId, e);
        }
    }

    private BenchmarkRunStatusUpdateDTO statusUpdate(BenchmarkRunStatusUpdateDTO.StatusEnum status, String reason) {
        return new BenchmarkRunStatusUpdateDTO().status(status).statusReason(reason);
    }

    private String logsSuffix(String containerId) {
        if (containerId == null) {
            return "";
        }

        try {
            String logs = containerPort.getContainerLogs(containerId, containerLogMaxBytes);
            if (logs == null || logs.isBlank()) {
                return "";
            }
            return "; logs: " + logs.strip();
        } catch (Exception e) {
            log.debug("Failed to read container logs for {}", containerId, e);
            return "";
        }
    }

    private String boundedReason(String reason) {
        if (reason == null) {
            return null;
        }

        if (reason.length() <= statusReasonMaxLength) {
            return reason;
        }

        String suffix = "...[truncated]";
        int available = Math.max(0, statusReasonMaxLength - suffix.length());
        return reason.substring(0, available) + suffix;
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

    private static final class StopRequestedException extends RuntimeException {
    }
}
