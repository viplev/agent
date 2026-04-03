package dk.viplev.agent.adapter.outbound.container.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveNetworkCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExporterLifecycleManagerTest {

    @Mock
    private DockerClient dockerClient;

    @Spy
    private ExporterLifecycleManager manager = new ExporterLifecycleManager(
            null, // replaced by @Spy field injection via setter — handled in setUp
            "gcr.io/cadvisor/cadvisor:v0.51.0",
            "prom/node-exporter:v1.9.0"
    );

    @BeforeEach
    void setUp() {
        manager = new ExporterLifecycleManager(
                dockerClient,
                "gcr.io/cadvisor/cadvisor:v0.51.0",
                "prom/node-exporter:v1.9.0"
        );
        // Spy on the manager to stub readSelfContainerId without file I/O
        manager = org.mockito.Mockito.spy(manager);
        lenient().doReturn("test-agent-container-id").when(manager).readSelfContainerId();
    }

    @Test
    void startup_createsNetworkConnectsAgentAndStartsContainers() {
        var networkResponse = mock(CreateNetworkResponse.class);
        when(networkResponse.getId()).thenReturn("net-id");
        mockNetworkCreation(networkResponse);
        mockConnectToNetwork();
        mockImagesPresent();
        mockEmptyContainerList();
        mockContainerCreationAndStart("cadvisor-id", "node-exporter-id");

        manager.start();

        verify(dockerClient).createNetworkCmd();
        verify(dockerClient).connectToNetworkCmd();
        verify(dockerClient).createContainerCmd("gcr.io/cadvisor/cadvisor:v0.51.0");
        verify(dockerClient).createContainerCmd("prom/node-exporter:v1.9.0");
        verify(dockerClient).startContainerCmd("cadvisor-id");
        verify(dockerClient).startContainerCmd("node-exporter-id");
    }

    @Test
    void startup_pullsImageIfNotPresentLocally() throws InterruptedException {
        var networkResponse = mock(CreateNetworkResponse.class);
        when(networkResponse.getId()).thenReturn("net-id");
        mockNetworkCreation(networkResponse);
        mockConnectToNetwork();
        mockEmptyContainerList();

        var inspectCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenThrow(new NotFoundException("No such image"));

        var pullCmd = mock(PullImageCmd.class);
        when(dockerClient.pullImageCmd(anyString())).thenReturn(pullCmd);
        var pullCallback = mock(PullImageResultCallback.class);
        when(pullCmd.exec(any(PullImageResultCallback.class))).thenReturn(pullCallback);
        when(pullCallback.awaitCompletion()).thenReturn(pullCallback);

        mockContainerCreationAndStart("cadvisor-id", "node-exporter-id");

        manager.start();

        verify(dockerClient).pullImageCmd("gcr.io/cadvisor/cadvisor:v0.51.0");
        verify(dockerClient).pullImageCmd("prom/node-exporter:v1.9.0");
    }

    @Test
    void startup_skipsContainerIfAlreadyPresent() {
        var networkResponse = mock(CreateNetworkResponse.class);
        when(networkResponse.getId()).thenReturn("net-id");
        mockNetworkCreation(networkResponse);
        mockConnectToNetwork();
        mockImagesPresent();

        var existingCadvisor = mock(Container.class);
        when(existingCadvisor.getNames()).thenReturn(new String[]{"/viplev-cadvisor"});

        var existingNodeExporter = mock(Container.class);
        when(existingNodeExporter.getNames()).thenReturn(new String[]{"/viplev-node-exporter"});

        var listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withNameFilter(any())).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec())
                .thenReturn(List.of(existingCadvisor))
                .thenReturn(List.of(existingNodeExporter));

        manager.start();

        verify(dockerClient, never()).createContainerCmd(anyString());
    }

    @Test
    void startup_handlesNetworkAlreadyExists() {
        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.exec()).thenThrow(new DockerException(
                "network with name viplev_agent already exists", 409));

        // Must also stub listNetworksCmd used in findNetworkId fallback
        var network = mock(Network.class);
        when(network.getName()).thenReturn(ExporterLifecycleManager.NETWORK_NAME);
        when(network.getId()).thenReturn("existing-net-id");
        var listNetworksCmd = mock(ListNetworksCmd.class);
        when(dockerClient.listNetworksCmd()).thenReturn(listNetworksCmd);
        when(listNetworksCmd.withNameFilter(anyString())).thenReturn(listNetworksCmd);
        when(listNetworksCmd.exec()).thenReturn(List.of(network));

        mockConnectToNetwork();
        mockImagesPresent();
        mockEmptyContainerList();
        mockContainerCreationAndStart("cadvisor-id", "node-exporter-id");

        manager.start();

        verify(dockerClient).createContainerCmd("gcr.io/cadvisor/cadvisor:v0.51.0");
        verify(dockerClient).createContainerCmd("prom/node-exporter:v1.9.0");
    }

    @Test
    void startup_throwsOnUnexpectedNetworkError() {
        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.exec()).thenThrow(new DockerException("connection refused", 500));

        assertThatThrownBy(() -> manager.start())
                .isInstanceOf(ContainerRuntimeException.class)
                .hasMessageContaining("Failed to create Docker network");
    }

    @Test
    void shutdown_stopsAndRemovesContainersAndNetwork() {
        var removeContainerCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd(anyString())).thenReturn(removeContainerCmd);
        when(removeContainerCmd.withForce(true)).thenReturn(removeContainerCmd);

        var network = mock(Network.class);
        when(network.getName()).thenReturn(ExporterLifecycleManager.NETWORK_NAME);
        when(network.getId()).thenReturn("net-id");

        var listNetworksCmd = mock(ListNetworksCmd.class);
        when(dockerClient.listNetworksCmd()).thenReturn(listNetworksCmd);
        when(listNetworksCmd.withNameFilter(anyString())).thenReturn(listNetworksCmd);
        when(listNetworksCmd.exec()).thenReturn(List.of(network));

        var removeNetworkCmd = mock(RemoveNetworkCmd.class);
        when(dockerClient.removeNetworkCmd("net-id")).thenReturn(removeNetworkCmd);

        manager.stop();

        verify(dockerClient).removeContainerCmd(ExporterLifecycleManager.CADVISOR_CONTAINER_NAME);
        verify(dockerClient).removeContainerCmd(ExporterLifecycleManager.NODE_EXPORTER_CONTAINER_NAME);
        verify(dockerClient).removeNetworkCmd("net-id");
    }

    @Test
    void shutdown_handlesAlreadyRemovedContainersGracefully() {
        var removeContainerCmd = mock(RemoveContainerCmd.class);
        when(dockerClient.removeContainerCmd(anyString())).thenReturn(removeContainerCmd);
        when(removeContainerCmd.withForce(true)).thenReturn(removeContainerCmd);
        when(removeContainerCmd.exec()).thenThrow(new DockerException("No such container", 404));

        var listNetworksCmd = mock(ListNetworksCmd.class);
        when(dockerClient.listNetworksCmd()).thenReturn(listNetworksCmd);
        when(listNetworksCmd.withNameFilter(anyString())).thenReturn(listNetworksCmd);
        when(listNetworksCmd.exec()).thenReturn(List.of());

        manager.stop();

        verify(dockerClient).removeContainerCmd(ExporterLifecycleManager.CADVISOR_CONTAINER_NAME);
        verify(dockerClient).removeContainerCmd(ExporterLifecycleManager.NODE_EXPORTER_CONTAINER_NAME);
    }

    // -- Helpers --

    private void mockNetworkCreation(CreateNetworkResponse response) {
        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.exec()).thenReturn(response);
    }

    private void mockConnectToNetwork() {
        var connectCmd = mock(ConnectToNetworkCmd.class);
        when(dockerClient.connectToNetworkCmd()).thenReturn(connectCmd);
        when(connectCmd.withNetworkId(anyString())).thenReturn(connectCmd);
        when(connectCmd.withContainerId(anyString())).thenReturn(connectCmd);
    }

    private void mockImagesPresent() {
        var inspectCmd = mock(InspectImageCmd.class);
        when(dockerClient.inspectImageCmd(anyString())).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(mock(InspectImageResponse.class));
    }

    private void mockEmptyContainerList() {
        var listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withNameFilter(any())).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of());
    }

    private void mockContainerCreationAndStart(String cadvisorId, String nodeExporterId) {
        mockCreateAndStart("gcr.io/cadvisor/cadvisor:v0.51.0", cadvisorId);
        mockCreateAndStart("prom/node-exporter:v1.9.0", nodeExporterId);
    }

    private void mockCreateAndStart(String image, String containerId) {
        var createCmd = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd(image)).thenReturn(createCmd);
        when(createCmd.withName(anyString())).thenReturn(createCmd);
        when(createCmd.withHostConfig(any(HostConfig.class))).thenReturn(createCmd);
        lenient().when(createCmd.withCmd(any(List.class))).thenReturn(createCmd);

        var response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn(containerId);
        when(createCmd.exec()).thenReturn(response);

        var startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd(containerId)).thenReturn(startCmd);
    }
}
