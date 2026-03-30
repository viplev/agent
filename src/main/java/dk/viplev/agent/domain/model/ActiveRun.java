package dk.viplev.agent.domain.model;

import java.util.UUID;

public record ActiveRun(
        UUID benchmarkId,
        UUID runId,
        String k6Instructions,
        BenchmarkRunStatus status
) {}
