package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.exception.AgentException;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class K6PerformanceStreamCoordinator implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(K6PerformanceStreamCoordinator.class);

    private final ViplevApiPort viplevApiPort;
    private final ContainerPort containerPort;
    private final UUID benchmarkId;
    private final UUID runId;
    private final String containerId;
    private final long flushIntervalMs;
    private final int maxBatchPoints;
    private final int sendMaxRetries;
    private final long sendBackoffMs;
    private final long finalFlushTimeoutMs;
    private final K6MetricAccumulator accumulator;

    private final AtomicLong lineCounter = new AtomicLong(0);
    private final AtomicReference<String> fatalError = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong sentMetricPoints = new AtomicLong(0);
    private final CountDownLatch senderDone = new CountDownLatch(1);
    private final java.util.concurrent.ExecutorService senderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "k6-performance-sender");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Closeable logStream;

    K6PerformanceStreamCoordinator(ViplevApiPort viplevApiPort,
                                   ContainerPort containerPort,
                                   UUID benchmarkId,
                                   UUID runId,
                                   String containerId,
                                   long flushIntervalMs,
                                   int maxBatchPoints,
                                   int maxBufferedPoints,
                                   int sendMaxRetries,
                                   long sendBackoffMs,
                                   long finalFlushTimeoutMs) {
        this.viplevApiPort = viplevApiPort;
        this.containerPort = containerPort;
        this.benchmarkId = benchmarkId;
        this.runId = runId;
        this.containerId = containerId;
        this.flushIntervalMs = Math.max(1L, flushIntervalMs);
        this.maxBatchPoints = Math.max(1, maxBatchPoints);
        this.sendMaxRetries = Math.max(1, sendMaxRetries);
        this.sendBackoffMs = Math.max(1L, sendBackoffMs);
        this.finalFlushTimeoutMs = Math.max(1L, finalFlushTimeoutMs);
        this.accumulator = new K6MetricAccumulator(maxBufferedPoints);
    }

    void start() {
        try {
            logStream = containerPort.followContainerLogs(containerId, this::onLogLine, this::onLogError);
        } catch (Exception e) {
            throw new AgentException("Failed to start K6 log streaming: " + e.getMessage(), e);
        }

        senderExecutor.submit(this::runSenderLoop);
    }

    String getFatalError() {
        return fatalError.get();
    }

    void finishAndAwait() {
        running.set(false);
        closeLogStream();
        senderExecutor.shutdownNow();

        try {
            if (!senderDone.await(finalFlushTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new AgentException("Timed out while flushing K6 performance metrics");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentException("Interrupted while flushing K6 performance metrics", e);
        }

        String error = fatalError.get();
        if (error != null) {
            throw new AgentException(error);
        }

        if (sentMetricPoints.get() == 0) {
            throw new AgentException("No K6 metrics found in output logs");
        }
    }

    @Override
    public void close() {
        running.set(false);
        closeLogStream();
        senderExecutor.shutdownNow();
        try {
            senderDone.await(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void onLogLine(String line) {
        if (!running.get()) {
            return;
        }

        long currentLine = lineCounter.incrementAndGet();
        try {
            accumulator.acceptLine(line, currentLine);
        } catch (K6MetricAccumulator.BufferOverflowException e) {
            setFatal("K6 performance metric buffer overflowed before metrics could be sent");
        } catch (Exception e) {
            setFatal("Failed to parse K6 output stream at line " + currentLine + ": " + e.getMessage());
        }
    }

    private void onLogError(Throwable throwable) {
        if (!running.get()) {
            return;
        }

        String reason = throwable == null || throwable.getMessage() == null
                ? "unknown error"
                : throwable.getMessage();
        setFatal("K6 log streaming failed: " + reason);
    }

    private void runSenderLoop() {
        try {
            while (running.get()) {
                if (fatalError.get() != null) {
                    return;
                }

                flushAvailableBatches();
                if (!sleep(flushIntervalMs)) {
                    break;
                }
            }

            flushAvailableBatches();
        } finally {
            senderDone.countDown();
            senderExecutor.shutdown();
        }
    }

    private void flushAvailableBatches() {
        while (true) {
            if (fatalError.get() != null) {
                return;
            }

            MetricPerformanceDTO batch = accumulator.drainBatch(maxBatchPoints);
            int metricCount = metricCount(batch);
            if (metricCount == 0) {
                return;
            }

            sendBatchWithRetry(batch, metricCount);
        }
    }

    private void sendBatchWithRetry(MetricPerformanceDTO batch, int metricCount) {
        Exception lastError = null;

        for (int attempt = 1; attempt <= sendMaxRetries; attempt++) {
            try {
                viplevApiPort.sendPerformanceMetrics(benchmarkId, runId, batch);
                sentMetricPoints.addAndGet(metricCount);
                return;
            } catch (Exception e) {
                lastError = e;
                if (attempt == sendMaxRetries) {
                    break;
                }
                sleep(sendBackoffMs);
            }
        }

        String reason = lastError == null ? "unknown error" : lastError.getMessage();
        setFatal("Failed to send K6 performance metrics after " + sendMaxRetries + " attempts: " + reason);
    }

    private int metricCount(MetricPerformanceDTO batch) {
        int httpCount = batch.getHttpMetrics() == null ? 0 : batch.getHttpMetrics().size();
        int vusCount = batch.getVusMetrics() == null ? 0 : batch.getVusMetrics().size();
        return httpCount + vusCount;
    }

    private void setFatal(String message) {
        if (fatalError.compareAndSet(null, message)) {
            log.warn("{} (benchmark={} run={})", message, benchmarkId, runId);
            running.set(false);
            closeLogStream();
        }
    }

    private void closeLogStream() {
        Closeable localLogStream = logStream;
        if (localLogStream == null) {
            return;
        }
        logStream = null;
        try {
            localLogStream.close();
        } catch (IOException e) {
            log.debug("Failed to close K6 log stream cleanly", e);
        }
    }

    private boolean sleep(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
