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

    public boolean startCollection(UUID benchmarkId, UUID runId) {
        return metricCollectionUseCase.startCollection(benchmarkId, runId);
    }

    public boolean stopCollection() {
        return metricCollectionUseCase.stopCollection();
    }
}
