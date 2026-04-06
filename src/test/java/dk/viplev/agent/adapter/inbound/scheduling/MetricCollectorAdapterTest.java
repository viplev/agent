package dk.viplev.agent.adapter.inbound.scheduling;

import dk.viplev.agent.port.inbound.MetricCollectionUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MetricCollectorAdapterTest {

    @Mock
    private MetricCollectionUseCase metricCollectionUseCase;

    private MetricCollectorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MetricCollectorAdapter(metricCollectionUseCase);
    }

    @Test
    void startCollection_delegatesToUseCase() {
        UUID benchmarkId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();

        adapter.startCollection(benchmarkId, runId);

        verify(metricCollectionUseCase).startCollection(benchmarkId, runId);
    }

    @Test
    void stopCollection_delegatesToUseCase() {
        adapter.stopCollection();

        verify(metricCollectionUseCase).stopCollection();
    }
}
