package dk.viplev.agent.port.inbound;

import java.util.UUID;

public interface BenchmarkExecutionUseCase {

    void startRun(UUID benchmarkId, UUID runId, String k6Instructions);

    void stopRun(UUID benchmarkId, UUID runId);
}
