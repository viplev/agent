package dk.viplev.agent.adapter.inbound.scheduling;

import dk.viplev.agent.domain.model.RunContext;
import dk.viplev.agent.generated.model.BenchmarkDTO;
import dk.viplev.agent.generated.model.MessageDTO;
import dk.viplev.agent.port.inbound.BenchmarkExecutionUseCase;
import dk.viplev.agent.port.inbound.ServiceDiscoveryUseCase;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessagePollingAdapterTest {

    @Mock
    private ViplevApiPort viplevApiPort;

    @Mock
    private BenchmarkExecutionUseCase benchmarkExecutionUseCase;

    @Mock
    private ServiceDiscoveryUseCase serviceDiscoveryUseCase;

    private RunContext runContext;
    private MessagePollingAdapter adapter;

    private static final UUID BENCHMARK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @BeforeEach
    void setUp() {
        runContext = new RunContext();
        adapter = new MessagePollingAdapter(viplevApiPort, benchmarkExecutionUseCase, serviceDiscoveryUseCase,
                runContext, 15_000, 5_000);
    }

    @Test
    void shouldPollImmediatelyWhenNeverPolledBefore() {
        when(viplevApiPort.pollMessages()).thenReturn(List.of());

        adapter.pollMessagesSafely(1_000);

        verify(serviceDiscoveryUseCase).syncServices();
        verify(viplevApiPort).pollMessages();
    }

    @Test
    void shouldRespectIdlePollingInterval() {
        when(viplevApiPort.pollMessages()).thenReturn(List.of());

        adapter.pollMessagesSafely(1_000);
        adapter.pollMessagesSafely(10_000);

        verify(viplevApiPort).pollMessages();
    }

    @Test
    void shouldUseActiveIntervalWhenRunIsActive() {
        when(viplevApiPort.pollMessages()).thenReturn(List.of());
        runContext.activate(BENCHMARK_ID, RUN_ID);

        adapter.pollMessagesSafely(1_000);
        adapter.pollMessagesSafely(5_500);

        verify(viplevApiPort, org.mockito.Mockito.times(1)).pollMessages();
        adapter.pollMessagesSafely(6_100);
        verify(viplevApiPort, org.mockito.Mockito.times(2)).pollMessages();
    }

    @Test
    void pendingStart_fetchesBenchmarkAndStartsRun() {
        var message = new MessageDTO()
                .benchmarkId(BENCHMARK_ID)
                .runId(RUN_ID)
                .messageType(MessageDTO.MessageTypeEnum.PENDING_START)
                .benchmarkData(new BenchmarkDTO().k6Instructions("script"));

        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely(1_000);

        verify(benchmarkExecutionUseCase).startRun(BENCHMARK_ID, RUN_ID, "script");
    }

    @Test
    void pendingStart_withoutBenchmarkData_doesNotStartRun() {
        var message = new MessageDTO()
                .benchmarkId(BENCHMARK_ID)
                .runId(RUN_ID)
                .messageType(MessageDTO.MessageTypeEnum.PENDING_START);

        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely(1_000);

        verify(benchmarkExecutionUseCase, never()).startRun(any(), any(), any());
    }

    @Test
    void pendingStop_stopsRun() {
        var message = new MessageDTO()
                .benchmarkId(BENCHMARK_ID)
                .runId(RUN_ID)
                .messageType(MessageDTO.MessageTypeEnum.PENDING_STOP);

        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely(1_000);

        verify(benchmarkExecutionUseCase).stopRun(BENCHMARK_ID, RUN_ID);
    }

    @Test
    void handlesOnlyFirstMessageWhenListContainsMultiple() {
        var first = new MessageDTO()
                .benchmarkId(BENCHMARK_ID)
                .runId(RUN_ID)
                .messageType(MessageDTO.MessageTypeEnum.PENDING_STOP);
        var second = new MessageDTO()
                .benchmarkId(UUID.randomUUID())
                .runId(UUID.randomUUID())
                .messageType(MessageDTO.MessageTypeEnum.PENDING_START);

        when(viplevApiPort.pollMessages()).thenReturn(List.of(first, second));

        adapter.pollMessagesSafely(1_000);

        verify(benchmarkExecutionUseCase).stopRun(BENCHMARK_ID, RUN_ID);
        verify(benchmarkExecutionUseCase, never()).startRun(any(), any(), any());
    }

    @Test
    void ignoresMessageWithNullType() {
        var message = new MessageDTO().benchmarkId(BENCHMARK_ID).runId(RUN_ID);
        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely(1_000);

        verify(benchmarkExecutionUseCase, never()).startRun(any(), any(), any());
        verify(benchmarkExecutionUseCase, never()).stopRun(any(), any());
    }

    @Test
    void ignoresMessageWithNullIds() {
        var message = new MessageDTO().messageType(MessageDTO.MessageTypeEnum.PENDING_START);
        when(viplevApiPort.pollMessages()).thenReturn(List.of(message));

        adapter.pollMessagesSafely(1_000);

        verify(benchmarkExecutionUseCase, never()).startRun(any(), any(), any());
    }

    @Test
    void pollingExceptionDoesNotPropagate() {
        doThrow(new RuntimeException("boom")).when(viplevApiPort).pollMessages();

        adapter.pollMessagesSafely(1_000);

        verify(benchmarkExecutionUseCase, never()).startRun(any(), any(), any());
        verify(benchmarkExecutionUseCase, never()).stopRun(any(), any());
    }

    @Test
    void pollingExceptionDoesNotDelayRetryByInterval() {
        doThrow(new RuntimeException("boom")).when(viplevApiPort).pollMessages();

        IntStream.range(0, 3).forEach(i -> adapter.pollMessagesSafely(1_000 + i));

        verify(viplevApiPort, times(3)).pollMessages();
    }

    @Test
    void initialServiceSyncFailure_blocksPollingUntilSyncSucceeds() {
        doThrow(new RuntimeException("sync failed"))
                .doNothing()
                .when(serviceDiscoveryUseCase).syncServices();
        when(viplevApiPort.pollMessages()).thenReturn(List.of());

        adapter.pollMessagesSafely(1_000);
        verify(viplevApiPort, never()).pollMessages();

        adapter.pollMessagesSafely(2_000);
        verify(viplevApiPort, times(1)).pollMessages();
    }
}
