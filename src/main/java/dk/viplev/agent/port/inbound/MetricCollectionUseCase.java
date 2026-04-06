package dk.viplev.agent.port.inbound;

import java.util.UUID;

public interface MetricCollectionUseCase {

    void startCollection(UUID benchmarkId, UUID runId);

    void stopCollection();
}
