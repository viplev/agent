package dk.viplev.agent.adapter.inbound.scheduling;

import dk.viplev.agent.port.inbound.MetricCollectionUseCase;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@Profile("docker")
public class MetricCollectorAdapter {

    private final MetricCollectionUseCase metricCollectionUseCase;

    public MetricCollectorAdapter(MetricCollectionUseCase metricCollectionUseCase) {
        this.metricCollectionUseCase = metricCollectionUseCase;
    }

    public void startCollection(UUID benchmarkId, UUID runId) {
        metricCollectionUseCase.startCollection(benchmarkId, runId);
    }

    public void stopCollection() {
        metricCollectionUseCase.stopCollection();
    }
}
