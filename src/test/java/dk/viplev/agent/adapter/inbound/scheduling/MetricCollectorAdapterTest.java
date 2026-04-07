package dk.viplev.agent.adapter.inbound.scheduling;

import dk.viplev.agent.generated.model.MessageDTO;
import dk.viplev.agent.port.inbound.MetricCollectionUseCase;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricCollectorAdapterTest {

    @Mock
    private MetricCollectionUseCase metricCollectionUseCase;

    @Mock
    private ViplevApiPort viplevApiPort;

    private MetricCollectorAdapter adapter;

    private static final UUID BENCHMARK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        adapter = new MetricCollectorAdapter(metricCollectionUseCase, viplevApiPort);
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

    @Test
    void polling_pendingStart_startsCollection() {
        var message = new MessageDTO()
                .benchmarkId(BENCHMARK_ID)
                .runId(RUN_ID)
                .messageType(MessageDTO.MessageTypeEnum.PENDING_START);
        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely();

        verify(metricCollectionUseCase).startCollection(BENCHMARK_ID, RUN_ID);
        verify(viplevApiPort).updateRunStatus(any(), any(), any());
    }

    @Test
    void polling_pendingStop_stopsCollection() {
        var message = new MessageDTO()
                .benchmarkId(BENCHMARK_ID)
                .runId(RUN_ID)
                .messageType(MessageDTO.MessageTypeEnum.PENDING_STOP);
        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely();

        verify(metricCollectionUseCase).stopCollection();
        verify(viplevApiPort).updateRunStatus(any(), any(), any());
    }

    @Test
    void polling_apiError_doesNotCrash() {
        doThrow(new RuntimeException("Connection refused")).when(viplevApiPort).pollMessages();

        // Must not throw
        adapter.pollMessagesSafely();

        verify(metricCollectionUseCase, never()).startCollection(any(), any());
        verify(metricCollectionUseCase, never()).stopCollection();
    }

    @Test
    void handleMessage_pendingStartWithNullBenchmarkId_ignoresMessage() {
        var message = new MessageDTO()
                .benchmarkId(null)
                .runId(RUN_ID)
                .messageType(MessageDTO.MessageTypeEnum.PENDING_START);
        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely();

        verify(metricCollectionUseCase, never()).startCollection(any(), any());
        verify(viplevApiPort, never()).updateRunStatus(any(), any(), any());
    }

    @Test
    void handleMessage_pendingStartWithNullRunId_ignoresMessage() {
        var message = new MessageDTO()
                .benchmarkId(BENCHMARK_ID)
                .runId(null)
                .messageType(MessageDTO.MessageTypeEnum.PENDING_START);
        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely();

        verify(metricCollectionUseCase, never()).startCollection(any(), any());
        verify(viplevApiPort, never()).updateRunStatus(any(), any(), any());
    }
}
