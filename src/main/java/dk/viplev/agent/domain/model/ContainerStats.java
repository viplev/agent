package dk.viplev.agent.domain.model;

public record ContainerStats(
        double cpuPercentage,
        long memoryUsageBytes,
        long memoryLimitBytes,
        long networkInBytes,
        long networkOutBytes,
        long blockInBytes,
        long blockOutBytes
) {}
