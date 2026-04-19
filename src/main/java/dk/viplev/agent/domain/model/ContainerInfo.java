package dk.viplev.agent.domain.model;

import java.time.LocalDateTime;

public record ContainerInfo(
        String id,
        String name,
        String serviceName,
        String imageName,
        String imageSha,
        String status,
        LocalDateTime startedAt,
        Long cpuLimit,
        Long cpuReservation,
        Long memoryLimit,
        Long memoryReservation
) {}
