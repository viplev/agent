package dk.viplev.agent.adapter.outbound.container.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.ConnectToNetworkCmd;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.CreateNetworkCmd;
import com.github.dockerjava.api.command.CreateNetworkResponse;
import com.github.dockerjava.api.command.CreateServiceCmd;
import com.github.dockerjava.api.command.CreateServiceResponse;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.InspectImageCmd;
import com.github.dockerjava.api.command.InspectImageResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.ListNetworksCmd;
import com.github.dockerjava.api.command.ListServicesCmd;
import com.github.dockerjava.api.command.PullImageCmd;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.RemoveContainerCmd;
import com.github.dockerjava.api.command.RemoveNetworkCmd;
import com.github.dockerjava.api.command.RemoveServiceCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.Service;
import com.github.dockerjava.api.model.ServiceSpec;
import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExporterLifecycleManagerTest {

    @Mock
    private DockerClient dockerClient;

    private ExporterLifecycleManager manager;

    @BeforeEach
    void setUp() {
        manager = spy(new ExporterLifecycleManager(
                dockerClient,
                "gcr.io/cadvisor/cadvisor:v0.51.0",
                "prom/node-exporter:v1.9.0",
                ExporterLifecycleManager.CADVISOR_CONTAINER_NAME,
                ExporterLifecycleManager.NODE_EXPORTER_CONTAINER_NAME
        ));
        lenient().doReturn("test-agent-container-id").when(manager).readSelfContainerId();
        lenient().doReturn(false).when(manager).isSwarmActive();
    }

    // -- Standalone mode tests --

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
    void startup_skipsContainerIfAlreadyRunning() {
        var networkResponse = mock(CreateNetworkResponse.class);
        when(networkResponse.getId()).thenReturn("net-id");
        mockNetworkCreation(networkResponse);
        mockConnectToNetwork();
        mockImagesPresent();

        var existingCadvisor = mock(Container.class);
        when(existingCadvisor.getNames()).thenReturn(new String[]{"/viplev-cadvisor"});
        when(existingCadvisor.getId()).thenReturn("existing-cadvisor-id");

        var existingNodeExporter = mock(Container.class);
        when(existingNodeExporter.getNames()).thenReturn(new String[]{"/viplev-node-exporter"});
        when(existingNodeExporter.getId()).thenReturn("existing-node-exporter-id");

        var listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withNameFilter(any())).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec())
                .thenReturn(List.of(existingCadvisor))
                .thenReturn(List.of(existingNodeExporter));

        mockRunningContainerState("existing-cadvisor-id");
        mockRunningContainerState("existing-node-exporter-id");

        manager.start();

        verify(dockerClient, never()).createContainerCmd(anyString());
    }

    @Test
    void startup_startsExistingStoppedContainer() {
        var networkResponse = mock(CreateNetworkResponse.class);
        when(networkResponse.getId()).thenReturn("net-id");
        mockNetworkCreation(networkResponse);
        mockConnectToNetwork();
        mockImagesPresent();

        var stoppedCadvisor = mock(Container.class);
        when(stoppedCadvisor.getNames()).thenReturn(new String[]{"/viplev-cadvisor"});
        when(stoppedCadvisor.getId()).thenReturn("stopped-cadvisor-id");

        var stoppedNodeExporter = mock(Container.class);
        when(stoppedNodeExporter.getNames()).thenReturn(new String[]{"/viplev-node-exporter"});
        when(stoppedNodeExporter.getId()).thenReturn("stopped-node-exporter-id");

        var listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.withNameFilter(any())).thenReturn(listCmd);
        when(listCmd.withShowAll(true)).thenReturn(listCmd);
        when(listCmd.exec())
                .thenReturn(List.of(stoppedCadvisor))
                .thenReturn(List.of(stoppedNodeExporter));

        mockStoppedContainerState("stopped-cadvisor-id");
        mockStoppedContainerState("stopped-node-exporter-id");

        var startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("stopped-cadvisor-id")).thenReturn(startCmd);
        when(dockerClient.startContainerCmd("stopped-node-exporter-id")).thenReturn(startCmd);

        manager.start();

        verify(dockerClient, never()).createContainerCmd(anyString());
        verify(dockerClient).startContainerCmd("stopped-cadvisor-id");
        verify(dockerClient).startContainerCmd("stopped-node-exporter-id");
    }

    @Test
    void startup_standaloneMode_createsBridgeNetwork() {
        var networkResponse = mock(CreateNetworkResponse.class);
        when(networkResponse.getId()).thenReturn("net-id");
        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.exec()).thenReturn(networkResponse);
        mockConnectToNetwork();
        mockImagesPresent();
        mockEmptyContainerList();
        mockContainerCreationAndStart("cadvisor-id", "node-exporter-id");

        manager.start();

        verify(createNetworkCmd).withDriver("bridge");
    }

    @Test
    void startup_handlesNetworkAlreadyExists() {
        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.exec()).thenThrow(new DockerException(
                "network with name viplev_agent already exists", 409));

        var network = mock(Network.class);
        when(network.getName()).thenReturn(ExporterLifecycleManager.NETWORK_NAME);
        when(network.getId()).thenReturn("existing-net-id");
        when(network.getDriver()).thenReturn("bridge");
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
    void startup_recreatesNetworkIfDriverMismatch() {
        doReturn(true).when(manager).isSwarmActive();

        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withAttachable(true)).thenReturn(createNetworkCmd);
        var createNetworkResponse = mock(CreateNetworkResponse.class);
        when(createNetworkResponse.getId()).thenReturn("new-net-id");
        when(createNetworkCmd.exec())
                .thenThrow(new DockerException("network with name viplev_agent already exists", 409))
                .thenReturn(createNetworkResponse);

        var existingNetwork = mock(Network.class);
        when(existingNetwork.getName()).thenReturn(ExporterLifecycleManager.NETWORK_NAME);
        when(existingNetwork.getId()).thenReturn("old-bridge-net-id");
        when(existingNetwork.getDriver()).thenReturn("bridge");
        when(existingNetwork.isAttachable()).thenReturn(null);
        var listNetworksCmd = mock(ListNetworksCmd.class);
        when(dockerClient.listNetworksCmd()).thenReturn(listNetworksCmd);
        when(listNetworksCmd.withNameFilter(anyString())).thenReturn(listNetworksCmd);
        when(listNetworksCmd.exec()).thenReturn(List.of(existingNetwork));

        var removeNetworkCmd = mock(RemoveNetworkCmd.class);
        when(dockerClient.removeNetworkCmd("old-bridge-net-id")).thenReturn(removeNetworkCmd);

        mockConnectToNetwork();
        mockSwarmServiceAbsent();
        mockSwarmServiceCreation();

        manager.start();

        verify(dockerClient).removeNetworkCmd("old-bridge-net-id");
        verify(createNetworkCmd, org.mockito.Mockito.times(2)).withDriver("overlay");
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

    // -- Swarm mode tests --

    @Test
    void startup_swarmMode_createsOverlayNetwork() {
        doReturn(true).when(manager).isSwarmActive();

        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withAttachable(true)).thenReturn(createNetworkCmd);
        var networkResponse = mock(CreateNetworkResponse.class);
        when(networkResponse.getId()).thenReturn("net-id");
        when(createNetworkCmd.exec()).thenReturn(networkResponse);

        mockConnectToNetwork();
        mockSwarmServiceAbsent();
        mockSwarmServiceCreation();

        manager.start();

        verify(createNetworkCmd).withDriver("overlay");
        verify(createNetworkCmd).withAttachable(true);
    }

    @Test
    void startup_swarmMode_connectsAgentToNetwork() {
        doReturn(true).when(manager).isSwarmActive();
        mockSwarmNetworkCreation();
        mockConnectToNetwork();
        mockSwarmServiceAbsent();
        mockSwarmServiceCreation();

        manager.start();

        verify(dockerClient).connectToNetworkCmd();
    }

    @Test
    void startup_swarmMode_createsGlobalServices() {
        doReturn(true).when(manager).isSwarmActive();
        mockSwarmNetworkCreation();
        mockConnectToNetwork();
        mockSwarmServiceAbsent();

        var createServiceCmd = mock(CreateServiceCmd.class);
        when(dockerClient.createServiceCmd(any(ServiceSpec.class))).thenReturn(createServiceCmd);
        var createServiceResponse = mock(CreateServiceResponse.class);
        when(createServiceCmd.exec()).thenReturn(createServiceResponse);

        manager.start();

        var captor = ArgumentCaptor.forClass(ServiceSpec.class);
        verify(dockerClient, org.mockito.Mockito.times(2)).createServiceCmd(captor.capture());

        List<ServiceSpec> specs = captor.getAllValues();
        assertThat(specs.get(0).getName()).isEqualTo(ExporterLifecycleManager.CADVISOR_CONTAINER_NAME);
        assertThat(specs.get(1).getName()).isEqualTo(ExporterLifecycleManager.NODE_EXPORTER_CONTAINER_NAME);
        assertThat(specs.get(0).getMode().getGlobal()).isNotNull();
        assertThat(specs.get(1).getMode().getGlobal()).isNotNull();
        assertThat(specs.get(0).getTaskTemplate().getNetworks().get(0).getTarget())
                .isEqualTo("net-id");
    }

    @Test
    void startup_swarmMode_skipsServiceIfAlreadyPresent() {
        doReturn(true).when(manager).isSwarmActive();
        mockSwarmNetworkCreation();
        mockConnectToNetwork();

        var listServicesCmd = mock(ListServicesCmd.class);
        when(dockerClient.listServicesCmd()).thenReturn(listServicesCmd);
        when(listServicesCmd.withNameFilter(any())).thenReturn(listServicesCmd);

        var cadvisorService = mock(Service.class);
        var cadvisorSpec = mock(ServiceSpec.class);
        when(cadvisorService.getSpec()).thenReturn(cadvisorSpec);
        when(cadvisorSpec.getName()).thenReturn(ExporterLifecycleManager.CADVISOR_CONTAINER_NAME);

        var nodeExporterService = mock(Service.class);
        var nodeExporterSpec = mock(ServiceSpec.class);
        when(nodeExporterService.getSpec()).thenReturn(nodeExporterSpec);
        when(nodeExporterSpec.getName()).thenReturn(ExporterLifecycleManager.NODE_EXPORTER_CONTAINER_NAME);

        when(listServicesCmd.exec())
                .thenReturn(List.of(cadvisorService))
                .thenReturn(List.of(nodeExporterService));

        manager.start();

        verify(dockerClient, never()).createServiceCmd(any(ServiceSpec.class));
    }

    @Test
    void shutdown_swarmMode_removesSwarmServices() {
        doReturn(true).when(manager).isSwarmActive();

        var removeServiceCmd = mock(RemoveServiceCmd.class);
        when(dockerClient.removeServiceCmd(anyString())).thenReturn(removeServiceCmd);

        var listNetworksCmd = mock(ListNetworksCmd.class);
        when(dockerClient.listNetworksCmd()).thenReturn(listNetworksCmd);
        when(listNetworksCmd.withNameFilter(anyString())).thenReturn(listNetworksCmd);
        when(listNetworksCmd.exec()).thenReturn(List.of());

        manager.stop();

        verify(dockerClient).removeServiceCmd(ExporterLifecycleManager.CADVISOR_CONTAINER_NAME);
        verify(dockerClient).removeServiceCmd(ExporterLifecycleManager.NODE_EXPORTER_CONTAINER_NAME);
        verify(dockerClient, never()).removeContainerCmd(anyString());
    }

    // -- Helpers --

    private void mockNetworkCreation(CreateNetworkResponse response) {
        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.exec()).thenReturn(response);
    }

    private void mockSwarmNetworkCreation() {
        var createNetworkCmd = mock(CreateNetworkCmd.class);
        when(dockerClient.createNetworkCmd()).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withName(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withDriver(anyString())).thenReturn(createNetworkCmd);
        when(createNetworkCmd.withAttachable(true)).thenReturn(createNetworkCmd);
        var networkResponse = mock(CreateNetworkResponse.class);
        when(networkResponse.getId()).thenReturn("net-id");
        when(createNetworkCmd.exec()).thenReturn(networkResponse);
    }

    private void mockSwarmServiceAbsent() {
        var listServicesCmd = mock(ListServicesCmd.class);
        when(dockerClient.listServicesCmd()).thenReturn(listServicesCmd);
        when(listServicesCmd.withNameFilter(any())).thenReturn(listServicesCmd);
        when(listServicesCmd.exec()).thenReturn(List.of());
    }

    private void mockSwarmServiceCreation() {
        var createServiceCmd = mock(CreateServiceCmd.class);
        when(dockerClient.createServiceCmd(any(ServiceSpec.class))).thenReturn(createServiceCmd);
        var createServiceResponse = mock(CreateServiceResponse.class);
        when(createServiceCmd.exec()).thenReturn(createServiceResponse);
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

    private void mockRunningContainerState(String containerId) {
        var inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
        var inspectResponse = mock(InspectContainerResponse.class);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        var state = mock(InspectContainerResponse.ContainerState.class);
        when(inspectResponse.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(true);
    }

    private void mockStoppedContainerState(String containerId) {
        var inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd(containerId)).thenReturn(inspectCmd);
        var inspectResponse = mock(InspectContainerResponse.class);
        when(inspectCmd.exec()).thenReturn(inspectResponse);
        var state = mock(InspectContainerResponse.ContainerState.class);
        when(inspectResponse.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(false);
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
