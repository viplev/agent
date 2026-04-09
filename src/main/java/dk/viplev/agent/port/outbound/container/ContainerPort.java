package dk.viplev.agent.port.outbound.container;

import dk.viplev.agent.domain.model.ContainerEvent;
import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.ContainerStartRequest;
import dk.viplev.agent.domain.model.ContainerStats;

import java.util.List;
import java.util.function.Consumer;

public interface ContainerPort {

    List<ContainerInfo> listContainers();

    ContainerStats getContainerStats(String containerId);

    String startContainer(ContainerStartRequest request);

    void stopContainer(String containerId);

    boolean isContainerRunning(String containerId);

    Long getContainerExitCode(String containerId);

    void watchContainerEvents(Consumer<ContainerEvent> callback);
}
