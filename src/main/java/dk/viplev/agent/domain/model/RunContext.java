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
        return currentRun.compareAndSet(null, new CurrentRun(benchmarkId, runId, null));
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
            CurrentRun updated = new CurrentRun(run.benchmarkId(), run.runId(), containerId);
            if (currentRun.compareAndSet(run, updated)) {
                return Optional.of(updated);
            }
        }
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

    public record CurrentRun(UUID benchmarkId, UUID runId, String k6ContainerId) {
    }
}
