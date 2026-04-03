package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.NodeInfo;
import dk.viplev.agent.generated.model.HostDTO;
import dk.viplev.agent.generated.model.ServiceDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;
import dk.viplev.agent.port.inbound.ServiceDiscoveryUseCase;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.discovery.NodeDiscoveryPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ServiceDiscoveryServiceImpl implements ServiceDiscoveryUseCase {

    private static final Logger log = LoggerFactory.getLogger(ServiceDiscoveryServiceImpl.class);

    private static final double NANO_CPUS_PER_CORE = 1_000_000_000.0;
    private static final double CPU_SHARES_PER_CORE = 1024.0;

    private final ContainerPort containerPort;
    private final ViplevApiPort viplevApiPort;
    private final NodeDiscoveryPort nodeDiscoveryPort;

    public ServiceDiscoveryServiceImpl(ContainerPort containerPort,
                                       ViplevApiPort viplevApiPort,
                                       NodeDiscoveryPort nodeDiscoveryPort) {
        this.containerPort = containerPort;
        this.viplevApiPort = viplevApiPort;
        this.nodeDiscoveryPort = nodeDiscoveryPort;
    }

    @Override
    public void syncServices() {
        var containers = containerPort.listContainers();
        List<NodeInfo> nodes = nodeDiscoveryPort.discoverNodes();
        var host = toHostDto(nodes.isEmpty() ? null : nodes.getFirst());
        var services = containers.stream()
                .map(this::toServiceDTO)
                .toList();

        var registration = new ServiceRegistrationDTO()
                .host(host)
                .services(services);

        viplevApiPort.registerServices(registration);
        log.info("Registered {} services with VIPLEV", services.size());
    }

    private HostDTO toHostDto(NodeInfo node) {
        if (node == null) {
            return new HostDTO()
                    .name("unknown")
                    .machineId("unknown")
                    .ipAddress("0.0.0.0")
                    .os("unknown");
        }
        return new HostDTO()
                .name(node.hostname())
                .machineId(node.machineId())
                .ipAddress(node.ipAddress())
                .os(node.os())
                .osVersion(node.osVersion())
                .cpuCores(node.cpuCores())
                .cpuThreads(node.cpuCores())
                .ramTotalBytes(node.ramTotalBytes());
    }

    private ServiceDTO toServiceDTO(ContainerInfo container) {
        return new ServiceDTO()
                .serviceName(container.name())
                .imageSha(container.imageSha())
                .imageName(container.imageName())
                .cpuLimit(container.cpuLimit() != null && container.cpuLimit() > 0
                        ? container.cpuLimit() / NANO_CPUS_PER_CORE : null)
                .cpuReservation(container.cpuReservation() != null && container.cpuReservation() > 0
                        ? container.cpuReservation() / CPU_SHARES_PER_CORE : null)
                .memoryLimitBytes(container.memoryLimit())
                .memoryReservationBytes(container.memoryReservation());
    }
}
