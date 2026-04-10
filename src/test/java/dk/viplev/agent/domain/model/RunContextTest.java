package dk.viplev.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RunContextTest {

    private static final UUID BENCHMARK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void activateSetsRunAsActive() {
        RunContext context = new RunContext();

        boolean activated = context.activate(BENCHMARK_ID, RUN_ID);

        assertThat(activated).isTrue();
        assertThat(context.isActive()).isTrue();
        assertThat(context.isActive(BENCHMARK_ID, RUN_ID)).isTrue();
    }

    @Test
    void activateFailsWhenAlreadyActive() {
        RunContext context = new RunContext();
        context.activate(BENCHMARK_ID, RUN_ID);

        boolean activated = context.activate(UUID.randomUUID(), UUID.randomUUID());

        assertThat(activated).isFalse();
        assertThat(context.isActive(BENCHMARK_ID, RUN_ID)).isTrue();
    }

    @Test
    void setContainerIdUpdatesActiveRun() {
        RunContext context = new RunContext();
        context.activate(BENCHMARK_ID, RUN_ID);

        var updated = context.setK6ContainerId("k6-1");

        assertThat(updated).isPresent();
        assertThat(updated.get().k6ContainerId()).isEqualTo("k6-1");
        assertThat(updated.get().status()).isEqualTo(BenchmarkRunStatus.PENDING_START);
    }

    @Test
    void markStatusIfMatchUpdatesStatus() {
        RunContext context = new RunContext();
        context.activate(BENCHMARK_ID, RUN_ID);

        boolean updated = context.markStatusIfMatch(BENCHMARK_ID, RUN_ID, BenchmarkRunStatus.STARTED);

        assertThat(updated).isTrue();
        assertThat(context.getCurrentRun()).isPresent();
        assertThat(context.getCurrentRun().get().status()).isEqualTo(BenchmarkRunStatus.STARTED);
    }

    @Test
    void deactivateIfMatchClearsOnlyMatchingRun() {
        RunContext context = new RunContext();
        context.activate(BENCHMARK_ID, RUN_ID);

        var wrongDeactivate = context.deactivateIfMatch(UUID.randomUUID(), UUID.randomUUID());
        var rightDeactivate = context.deactivateIfMatch(BENCHMARK_ID, RUN_ID);

        assertThat(wrongDeactivate).isEmpty();
        assertThat(rightDeactivate).isPresent();
        assertThat(context.isActive()).isFalse();
    }

    @Test
    void markStatusIfMatch_doesNotAllowPendingStopToStarted() {
        RunContext context = new RunContext();
        context.activate(BENCHMARK_ID, RUN_ID);
        context.markStatusIfMatch(BENCHMARK_ID, RUN_ID, BenchmarkRunStatus.PENDING_STOP);

        boolean updated = context.markStatusIfMatch(BENCHMARK_ID, RUN_ID, BenchmarkRunStatus.STARTED);

        assertThat(updated).isFalse();
        assertThat(context.getCurrentRun()).isPresent();
        assertThat(context.getCurrentRun().get().status()).isEqualTo(BenchmarkRunStatus.PENDING_STOP);
    }
}
