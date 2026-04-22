package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import dk.viplev.agent.domain.exception.ViplevApiException;
import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.NodeInfo;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.discovery.NodeDiscoveryPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServiceDiscoveryServiceImplTest {

    @Mock
    private ContainerPort containerPort;

    @Mock
    private ViplevApiPort viplevApiPort;

    @Mock
    private NodeDiscoveryPort nodeDiscoveryPort;

    private ServiceDiscoveryServiceImpl service;

    private final NodeInfo testNode = new NodeInfo(
            "daemon-id-abc123", "test-host", "192.168.1.1",
            "Linux", "5.15.0", 8, 16_000_000_000L);

    @BeforeEach
    void setUp() {
        service = new ServiceDiscoveryServiceImpl(containerPort, viplevApiPort, nodeDiscoveryPort);
    }

    @Test
    void syncServices_registersContainersWithHostInfo() {
        var containers = List.of(
                new ContainerInfo("id1", "nginx", null, "nginx:latest", "sha256:aaa", "running",
                        null, 2_000_000_000L, 1024L, 536_870_912L, 268_435_456L),
                new ContainerInfo("id2", "redis", null, "redis:7", "sha256:bbb", "running",
                        null, null, null, null, null)
        );
        when(containerPort.listContainers()).thenReturn(containers);
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var registration = captor.getValue();
        assertThat(registration.getHosts()).hasSize(1);

        var host = registration.getHosts().getFirst();
        assertThat(host.getMachineId()).isEqualTo("daemon-id-abc123");
        assertThat(host.getName()).isEqualTo("test-host");
        assertThat(host.getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(host.getOs()).isEqualTo("Linux");
        assertThat(host.getCpuCores()).isNull();
        assertThat(host.getCpuThreads()).isEqualTo(8);
        assertThat(host.getRamTotalBytes()).isEqualTo(16_000_000_000L);
        
        assertThat(registration.getServices()).hasSize(2);
        
        var nginxService = registration.getServices().stream()
                .filter(s -> s.getServiceName().equals("nginx"))
                .findFirst()
                .orElseThrow();
        assertThat(nginxService.getImageName()).isEqualTo("nginx:latest");
        assertThat(nginxService.getImageSha()).isEqualTo("sha256:aaa");
        
        var redisService = registration.getServices().stream()
                .filter(s -> s.getServiceName().equals("redis"))
                .findFirst()
                .orElseThrow();
        assertThat(redisService.getServiceName()).isEqualTo("redis");
    }

    @Test
    void syncServices_multipleNodes_onlyLocalNodeGetsServices() {
        var node2 = new NodeInfo("daemon-id-xyz", "node2", "192.168.1.2", "Linux", "5.15.0", 4, 8_000_000_000L);
        when(containerPort.listContainers()).thenReturn(List.of(
                new ContainerInfo("id1", "nginx", null, "nginx:latest", "sha256:aaa", "running", null, null, null, null, null)));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode, node2));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var hosts = captor.getValue().getHosts();
        assertThat(hosts).hasSize(2);
        assertThat(hosts.get(0).getMachineId()).isEqualTo("daemon-id-abc123");
        assertThat(hosts.get(1).getMachineId()).isEqualTo("daemon-id-xyz");
        
        var services = captor.getValue().getServices();
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getReplicas()).hasSize(1);
        assertThat(services.get(0).getReplicas().get(0).getMachineId()).isEqualTo("daemon-id-abc123");
    }

    @Test
    void syncServices_convertsNanoCpusToCores() {
        var container = new ContainerInfo("id1", "app", null, "app:1", "sha256:ccc", "running",
                null, 2_000_000_000L, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var service = captor.getValue().getServices().stream()
                .filter(s -> s.getServiceName().equals("app"))
                .findFirst()
                .orElseThrow();
        assertThat(service.getCpuLimit()).isEqualTo(2.0);
    }

    @Test
    void syncServices_convertsCpuSharesToCores() {
        var container = new ContainerInfo("id1", "app", null, "app:1", "sha256:ccc", "running",
                null, null, 1024L, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var service = captor.getValue().getServices().stream()
                .filter(s -> s.getServiceName().equals("app"))
                .findFirst()
                .orElseThrow();
        assertThat(service.getCpuReservation()).isEqualTo(1.0);
    }

    @Test
    void syncServices_handlesNullResourceFields() {
        var container = new ContainerInfo("id1", "app", null, "app:1", "sha256:ccc", "running",
                null, null, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var service = captor.getValue().getServices().stream()
                .filter(s -> s.getServiceName().equals("app"))
                .findFirst()
                .orElseThrow();
        assertThat(service.getCpuLimit()).isNull();
        assertThat(service.getCpuReservation()).isNull();
        assertThat(service.getMemoryLimitBytes()).isNull();
        assertThat(service.getMemoryReservationBytes()).isNull();
    }

    @Test
    void syncServices_emptyNodeList_registersEmptyHosts() {
        when(containerPort.listContainers()).thenReturn(List.of());
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of());
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        assertThat(captor.getValue().getHosts()).isEmpty();
        assertThat(captor.getValue().getServices()).isEmpty();
    }

    @Test
    void syncServices_propagatesContainerPortException() {
        when(containerPort.listContainers()).thenThrow(new ContainerRuntimeException("Docker unavailable"));

        assertThatThrownBy(() -> service.syncServices())
                .isInstanceOf(ContainerRuntimeException.class);
    }

    @Test
    void syncServices_propagatesApiException() {
        when(containerPort.listContainers()).thenReturn(List.of());
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");
        doThrow(new ViplevApiException("API error", 500))
                .when(viplevApiPort).registerServices(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.syncServices())
                .isInstanceOf(ViplevApiException.class);
    }

    @Test
    void syncServices_usesServiceNameFromContainerInfo() {
        var container = new ContainerInfo("id1", "nginx-container-1", "nginx", "nginx:latest", "sha256:aaa", "running",
                null, null, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var services = captor.getValue().getServices();
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getServiceName()).isEqualTo("nginx");
    }

    @Test
    void syncServices_usesServiceNameWhenPresent() {
        var container = new ContainerInfo("id1", "nginx.1.abc123", "nginx-swarm", "nginx:latest", "sha256:aaa", "running",
                null, null, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var services = captor.getValue().getServices();
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getServiceName()).isEqualTo("nginx-swarm");
    }

    @Test
    void syncServices_fallsBackToContainerNameWhenServiceNameNull() {
        var container = new ContainerInfo("id1", "standalone-nginx", null, "nginx:latest", "sha256:aaa", "running",
                null, null, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var services = captor.getValue().getServices();
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getServiceName()).isEqualTo("standalone-nginx");
    }

    @Test
    void syncServices_fallsBackToContainerIdWhenServiceNameAndNameBlank() {
        var container = new ContainerInfo("id1", "", "  ", "nginx:latest", "sha256:aaa", "running",
                null, null, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var services = captor.getValue().getServices();
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getServiceName()).isEqualTo("id1");
    }

    @Test
    void syncServices_usesContainerIdForReplicaNameWhenNameBlank() {
        var container = new ContainerInfo("id1", "", "nginx", "nginx:latest", "sha256:aaa", "running",
                null, null, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var services = captor.getValue().getServices();
        assertThat(services).hasSize(1);
        assertThat(services.get(0).getReplicas()).hasSize(1);
        assertThat(services.get(0).getReplicas().get(0).getContainerName()).isEqualTo("id1");
    }

    @Test
    void syncServices_groupsMultipleReplicasByServiceName() {
        var startedAt1 = LocalDateTime.of(2026, 4, 22, 10, 0, 0);
        var startedAt2 = LocalDateTime.of(2026, 4, 22, 10, 1, 0);
        var containers = List.of(
                new ContainerInfo("id1", "nginx-1", "nginx", "nginx:latest", "sha256:aaa", "running",
                        startedAt1, 2_000_000_000L, null, 536_870_912L, null),
                new ContainerInfo("id2", "nginx-2", "nginx", "nginx:latest", "sha256:aaa", "running",
                        startedAt2, 2_000_000_000L, null, 536_870_912L, null),
                new ContainerInfo("id3", "redis-1", "redis", "redis:7", "sha256:bbb", "running",
                        startedAt1, null, null, null, null)
        );
        when(containerPort.listContainers()).thenReturn(containers);
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("daemon-id-abc123");

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var services = captor.getValue().getServices();
        assertThat(services).hasSize(2);

        var nginxService = services.stream()
                .filter(s -> s.getServiceName().equals("nginx"))
                .findFirst()
                .orElseThrow();
        assertThat(nginxService.getReplicas()).hasSize(2);
        assertThat(nginxService.getReplicas().get(0).getContainerId()).isEqualTo("id1");
        assertThat(nginxService.getReplicas().get(0).getContainerName()).isEqualTo("nginx-1");
        assertThat(nginxService.getReplicas().get(0).getMachineId()).isEqualTo("daemon-id-abc123");
        assertThat(nginxService.getReplicas().get(0).getStartedAt()).isEqualTo(startedAt1);
        assertThat(nginxService.getReplicas().get(1).getContainerId()).isEqualTo("id2");
        assertThat(nginxService.getReplicas().get(1).getContainerName()).isEqualTo("nginx-2");
        assertThat(nginxService.getReplicas().get(1).getStartedAt()).isEqualTo(startedAt2);

        var redisService = services.stream()
                .filter(s -> s.getServiceName().equals("redis"))
                .findFirst()
                .orElseThrow();
        assertThat(redisService.getReplicas()).hasSize(1);
        assertThat(redisService.getReplicas().get(0).getContainerId()).isEqualTo("id3");
    }
}
