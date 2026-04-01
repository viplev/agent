package dk.viplev.agent.domain.model;

import java.time.Instant;

public record ContainerEvent(
        String containerId,
        String containerName,
        EventType eventType,
        Instant timestamp
) {

    public enum EventType {
        STARTED, STOPPED, DIED
    }
}
