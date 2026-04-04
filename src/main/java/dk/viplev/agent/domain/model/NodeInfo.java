package dk.viplev.agent.domain.model;

public record NodeInfo(
        String machineId,
        String hostname,
        String ipAddress,
        String os,
        String osVersion,
        int logicalCpuCount,
        long ramTotalBytes
) {}
