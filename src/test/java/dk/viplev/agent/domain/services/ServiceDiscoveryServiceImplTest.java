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
                new ContainerInfo("id1", "nginx", "nginx:latest", "sha256:aaa", "running",
                        2_000_000_000L, 1024L, 536_870_912L, 268_435_456L),
                new ContainerInfo("id2", "redis", "redis:7", "sha256:bbb", "running",
                        null, null, null, null)
        );
        when(containerPort.listContainers()).thenReturn(containers);
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var registration = captor.getValue();
        assertThat(registration.getHosts()).hasSize(1);

        var hostEntry = registration.getHosts().getFirst();
        assertThat(hostEntry.getHost().getMachineId()).isEqualTo("daemon-id-abc123");
        assertThat(hostEntry.getHost().getName()).isEqualTo("test-host");
        assertThat(hostEntry.getHost().getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(hostEntry.getHost().getOs()).isEqualTo("Linux");
        assertThat(hostEntry.getHost().getCpuCores()).isEqualTo(8);
        assertThat(hostEntry.getHost().getRamTotalBytes()).isEqualTo(16_000_000_000L);
        assertThat(hostEntry.getServices()).hasSize(2);

        assertThat(hostEntry.getServices().get(0).getServiceName()).isEqualTo("nginx");
        assertThat(hostEntry.getServices().get(0).getImageName()).isEqualTo("nginx:latest");
        assertThat(hostEntry.getServices().get(0).getImageSha()).isEqualTo("sha256:aaa");
        assertThat(hostEntry.getServices().get(1).getServiceName()).isEqualTo("redis");
    }

    @Test
    void syncServices_multipleNodes_eachGetsServices() {
        var node2 = new NodeInfo("daemon-id-xyz", "node2", "192.168.1.2", "Linux", "5.15.0", 4, 8_000_000_000L);
        when(containerPort.listContainers()).thenReturn(List.of(
                new ContainerInfo("id1", "nginx", "nginx:latest", "sha256:aaa", "running", null, null, null, null)));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode, node2));

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var hosts = captor.getValue().getHosts();
        assertThat(hosts).hasSize(2);
        assertThat(hosts.get(0).getHost().getMachineId()).isEqualTo("daemon-id-abc123");
        assertThat(hosts.get(1).getHost().getMachineId()).isEqualTo("daemon-id-xyz");
        assertThat(hosts.get(0).getServices()).hasSize(1);
        assertThat(hosts.get(1).getServices()).hasSize(1);
    }

    @Test
    void syncServices_convertsNanoCpusToCores() {
        var container = new ContainerInfo("id1", "app", "app:1", "sha256:ccc", "running",
                2_000_000_000L, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        assertThat(captor.getValue().getHosts().getFirst().getServices().get(0).getCpuLimit()).isEqualTo(2.0);
    }

    @Test
    void syncServices_convertsCpuSharesToCores() {
        var container = new ContainerInfo("id1", "app", "app:1", "sha256:ccc", "running",
                null, 1024L, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        assertThat(captor.getValue().getHosts().getFirst().getServices().get(0).getCpuReservation()).isEqualTo(1.0);
    }

    @Test
    void syncServices_handlesNullResourceFields() {
        var container = new ContainerInfo("id1", "app", "app:1", "sha256:ccc", "running",
                null, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(testNode));

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var dto = captor.getValue().getHosts().getFirst().getServices().get(0);
        assertThat(dto.getCpuLimit()).isNull();
        assertThat(dto.getCpuReservation()).isNull();
        assertThat(dto.getMemoryLimitBytes()).isNull();
        assertThat(dto.getMemoryReservationBytes()).isNull();
    }

    @Test
    void syncServices_emptyNodeList_registersEmptyHosts() {
        when(containerPort.listContainers()).thenReturn(List.of());
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of());

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        assertThat(captor.getValue().getHosts()).isEmpty();
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
        doThrow(new ViplevApiException("API error", 500))
                .when(viplevApiPort).registerServices(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.syncServices())
                .isInstanceOf(ViplevApiException.class);
    }
}
