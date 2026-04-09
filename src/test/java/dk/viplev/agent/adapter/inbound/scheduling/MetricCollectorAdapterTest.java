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

    private static final UUID BENCHMARK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        adapter = new MetricCollectorAdapter(metricCollectionUseCase);
    }

    @Test
    void startCollection_delegatesToUseCase() {
        adapter.startCollection(BENCHMARK_ID, RUN_ID);

        verify(metricCollectionUseCase).startCollection(BENCHMARK_ID, RUN_ID);
    }

    @Test
    void stopCollection_delegatesToUseCase() {
        adapter.stopCollection();

        verify(metricCollectionUseCase).stopCollection();
    }
}
