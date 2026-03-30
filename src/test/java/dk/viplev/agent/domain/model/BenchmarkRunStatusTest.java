package dk.viplev.agent.domain.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BenchmarkRunStatusTest {

    @Test
    void shouldHaveExactlySixValues() {
        assertThat(BenchmarkRunStatus.values()).hasSize(6);
    }

    @Test
    void shouldMatchOpenApiSpecValues() {
        assertThat(BenchmarkRunStatus.valueOf("PENDING_START")).isEqualTo(BenchmarkRunStatus.PENDING_START);
        assertThat(BenchmarkRunStatus.valueOf("STARTED")).isEqualTo(BenchmarkRunStatus.STARTED);
        assertThat(BenchmarkRunStatus.valueOf("PENDING_STOP")).isEqualTo(BenchmarkRunStatus.PENDING_STOP);
        assertThat(BenchmarkRunStatus.valueOf("STOPPED")).isEqualTo(BenchmarkRunStatus.STOPPED);
        assertThat(BenchmarkRunStatus.valueOf("FINISHED")).isEqualTo(BenchmarkRunStatus.FINISHED);
        assertThat(BenchmarkRunStatus.valueOf("FAILED")).isEqualTo(BenchmarkRunStatus.FAILED);
    }
}
