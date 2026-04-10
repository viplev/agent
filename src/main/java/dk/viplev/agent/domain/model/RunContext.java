package dk.viplev.agent.domain.model;

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class RunContext {

    private final AtomicReference<CurrentRun> currentRun = new AtomicReference<>();

    public Optional<CurrentRun> getCurrentRun() {
        return Optional.ofNullable(currentRun.get());
    }

    public boolean isActive() {
        return currentRun.get() != null;
    }

    public boolean activate(UUID benchmarkId, UUID runId) {
        return currentRun.compareAndSet(null, new CurrentRun(benchmarkId, runId, null, BenchmarkRunStatus.PENDING_START));
    }

    public boolean isActive(UUID benchmarkId, UUID runId) {
        CurrentRun run = currentRun.get();
        return run != null && run.benchmarkId().equals(benchmarkId) && run.runId().equals(runId);
    }

    public Optional<CurrentRun> setK6ContainerId(String containerId) {
        while (true) {
            CurrentRun run = currentRun.get();
            if (run == null) {
                return Optional.empty();
            }
            CurrentRun updated = new CurrentRun(run.benchmarkId(), run.runId(), containerId, run.status());
            if (currentRun.compareAndSet(run, updated)) {
                return Optional.of(updated);
            }
        }
    }

    public Optional<CurrentRun> setK6ContainerIdIfMatch(UUID benchmarkId, UUID runId, String containerId) {
        while (true) {
            CurrentRun run = currentRun.get();
            if (run == null) {
                return Optional.empty();
            }
            if (!run.benchmarkId().equals(benchmarkId) || !run.runId().equals(runId)) {
                return Optional.empty();
            }
            CurrentRun updated = new CurrentRun(run.benchmarkId(), run.runId(), containerId, run.status());
            if (currentRun.compareAndSet(run, updated)) {
                return Optional.of(updated);
            }
        }
    }

    public Optional<CurrentRun> setK6ContainerIdIfMatchAndNotStopped(UUID benchmarkId, UUID runId, String containerId) {
        while (true) {
            CurrentRun run = currentRun.get();
            if (run == null) {
                return Optional.empty();
            }
            if (!run.benchmarkId().equals(benchmarkId) || !run.runId().equals(runId)) {
                return Optional.empty();
            }
            if (run.status() == BenchmarkRunStatus.PENDING_STOP || run.status() == BenchmarkRunStatus.STOPPED) {
                return Optional.empty();
            }
            CurrentRun updated = new CurrentRun(run.benchmarkId(), run.runId(), containerId, run.status());
            if (currentRun.compareAndSet(run, updated)) {
                return Optional.of(updated);
            }
        }
    }

    public boolean markStatusIfMatch(UUID benchmarkId, UUID runId, BenchmarkRunStatus status) {
        while (true) {
            CurrentRun run = currentRun.get();
            if (run == null) {
                return false;
            }
            if (!run.benchmarkId().equals(benchmarkId) || !run.runId().equals(runId)) {
                return false;
            }
            if (run.status() == status) {
                return true;
            }
            if (isTerminal(run.status())) {
                return false;
            }
            if (!isAllowedTransition(run.status(), status)) {
                return false;
            }
            CurrentRun updated = new CurrentRun(run.benchmarkId(), run.runId(), run.k6ContainerId(), status);
            if (currentRun.compareAndSet(run, updated)) {
                return true;
            }
        }
    }

    public boolean isStopped(UUID benchmarkId, UUID runId) {
        CurrentRun run = currentRun.get();
        return run != null
                && run.benchmarkId().equals(benchmarkId)
                && run.runId().equals(runId)
                && run.status() == BenchmarkRunStatus.STOPPED;
    }

    public boolean isStopRequested(UUID benchmarkId, UUID runId) {
        CurrentRun run = currentRun.get();
        return run != null
                && run.benchmarkId().equals(benchmarkId)
                && run.runId().equals(runId)
                && (run.status() == BenchmarkRunStatus.PENDING_STOP || run.status() == BenchmarkRunStatus.STOPPED);
    }

    private boolean isTerminal(BenchmarkRunStatus status) {
        return status == BenchmarkRunStatus.STOPPED
                || status == BenchmarkRunStatus.FINISHED
                || status == BenchmarkRunStatus.FAILED;
    }

    private boolean isAllowedTransition(BenchmarkRunStatus from, BenchmarkRunStatus to) {
        return switch (from) {
            case PENDING_START -> to == BenchmarkRunStatus.STARTED
                    || to == BenchmarkRunStatus.PENDING_STOP
                    || to == BenchmarkRunStatus.FAILED;
            case STARTED -> to == BenchmarkRunStatus.PENDING_STOP
                    || to == BenchmarkRunStatus.FINISHED
                    || to == BenchmarkRunStatus.FAILED;
            case PENDING_STOP -> to == BenchmarkRunStatus.STOPPED || to == BenchmarkRunStatus.FAILED;
            case STOPPED, FINISHED, FAILED -> false;
        };
    }

    public Optional<CurrentRun> deactivateIfMatch(UUID benchmarkId, UUID runId) {
        while (true) {
            CurrentRun run = currentRun.get();
            if (run == null) {
                return Optional.empty();
            }
            if (!run.benchmarkId().equals(benchmarkId) || !run.runId().equals(runId)) {
                return Optional.empty();
            }
            if (currentRun.compareAndSet(run, null)) {
                return Optional.of(run);
            }
        }
    }

    public Optional<CurrentRun> deactivate() {
        return Optional.ofNullable(currentRun.getAndSet(null));
    }

    public record CurrentRun(UUID benchmarkId, UUID runId, String k6ContainerId, BenchmarkRunStatus status) {
    }
}
