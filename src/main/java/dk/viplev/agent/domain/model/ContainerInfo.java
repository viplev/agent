package dk.viplev.agent.domain.model;

public record ContainerInfo(
        String id,
        String name,
        String imageName,
        String imageSha,
        String status,
        Long cpuLimit,
        Long cpuReservation,
        Long memoryLimit,
        Long memoryReservation
) {}
