package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.NodeInfo;
import dk.viplev.agent.generated.model.HostDTO;
import dk.viplev.agent.generated.model.ServiceDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationHostDTO;
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
        var services = containers.stream().map(this::toServiceDTO).toList();
        var localNodeId = nodeDiscoveryPort.getLocalNodeId();
        var hosts = nodeDiscoveryPort.discoverNodes().stream()
                .map(node -> new ServiceRegistrationHostDTO()
                        .host(toHostDto(node))
                        .services(node.machineId().equals(localNodeId) ? services : List.of()))
                .toList();

        viplevApiPort.registerServices(new ServiceRegistrationDTO().hosts(hosts));
        log.info("Registered {} services on local node, {} host(s) total with VIPLEV", services.size(), hosts.size());
    }

    private HostDTO toHostDto(NodeInfo node) {
        return new HostDTO()
                .name(node.hostname())
                .machineId(node.machineId())
                .ipAddress(node.ipAddress())
                .os(node.os())
                .osVersion(node.osVersion())
                .cpuCores(null) // physical core count not available from Docker API; optional field
                .cpuThreads(node.logicalCpuCount())
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
