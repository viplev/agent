package dk.viplev.agent.port.inbound;

import java.util.UUID;

public interface MetricCollectionUseCase {

    boolean startCollection(UUID benchmarkId, UUID runId);

    boolean stopCollection();
}
