package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import dk.viplev.agent.domain.exception.ViplevApiException;
import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.generated.model.ServiceDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.host.HostInfoProvider;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import dk.viplev.agent.generated.model.HostDTO;
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
    private HostInfoProvider hostInfoProvider;

    private ServiceDiscoveryServiceImpl service;

    private final HostDTO testHost = new HostDTO()
            .name("test-host")
            .machineId("abc123")
            .ipAddress("192.168.1.1")
            .os("Linux");

    @BeforeEach
    void setUp() {
        service = new ServiceDiscoveryServiceImpl(containerPort, viplevApiPort, hostInfoProvider);
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
        when(hostInfoProvider.getHostInfo()).thenReturn(testHost);

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        var registration = captor.getValue();
        assertThat(registration.getHost()).isEqualTo(testHost);
        assertThat(registration.getServices()).hasSize(2);

        var nginx = registration.getServices().get(0);
        assertThat(nginx.getServiceName()).isEqualTo("nginx");
        assertThat(nginx.getImageName()).isEqualTo("nginx:latest");
        assertThat(nginx.getImageSha()).isEqualTo("sha256:aaa");

        var redis = registration.getServices().get(1);
        assertThat(redis.getServiceName()).isEqualTo("redis");
        assertThat(redis.getImageName()).isEqualTo("redis:7");
    }

    @Test
    void syncServices_convertsNanoCpusToCores() {
        var container = new ContainerInfo("id1", "app", "app:1", "sha256:ccc", "running",
                2_000_000_000L, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(hostInfoProvider.getHostInfo()).thenReturn(testHost);

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        ServiceDTO dto = captor.getValue().getServices().get(0);
        assertThat(dto.getCpuLimit()).isEqualTo(2.0);
    }

    @Test
    void syncServices_convertsCpuSharesToCores() {
        var container = new ContainerInfo("id1", "app", "app:1", "sha256:ccc", "running",
                null, 1024L, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(hostInfoProvider.getHostInfo()).thenReturn(testHost);

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        ServiceDTO dto = captor.getValue().getServices().get(0);
        assertThat(dto.getCpuReservation()).isEqualTo(1.0);
    }

    @Test
    void syncServices_handlesNullResourceFields() {
        var container = new ContainerInfo("id1", "app", "app:1", "sha256:ccc", "running",
                null, null, null, null);
        when(containerPort.listContainers()).thenReturn(List.of(container));
        when(hostInfoProvider.getHostInfo()).thenReturn(testHost);

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        ServiceDTO dto = captor.getValue().getServices().get(0);
        assertThat(dto.getCpuLimit()).isNull();
        assertThat(dto.getCpuReservation()).isNull();
        assertThat(dto.getMemoryLimitBytes()).isNull();
        assertThat(dto.getMemoryReservationBytes()).isNull();
    }

    @Test
    void syncServices_emptyContainerList_registersHostOnly() {
        when(containerPort.listContainers()).thenReturn(List.of());
        when(hostInfoProvider.getHostInfo()).thenReturn(testHost);

        service.syncServices();

        var captor = ArgumentCaptor.forClass(ServiceRegistrationDTO.class);
        verify(viplevApiPort).registerServices(captor.capture());

        assertThat(captor.getValue().getHost()).isEqualTo(testHost);
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
        when(hostInfoProvider.getHostInfo()).thenReturn(testHost);
        doThrow(new ViplevApiException("API error", 500))
                .when(viplevApiPort).registerServices(org.mockito.ArgumentMatchers.any());

        assertThatThrownBy(() -> service.syncServices())
                .isInstanceOf(ViplevApiException.class);
    }
}
