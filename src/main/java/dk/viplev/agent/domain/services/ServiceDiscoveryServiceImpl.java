package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.NodeInfo;
import dk.viplev.agent.generated.model.HostDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationServiceDTO;
import dk.viplev.agent.generated.model.ServiceReplicaDTO;
import dk.viplev.agent.port.inbound.ServiceDiscoveryUseCase;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.discovery.NodeDiscoveryPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        var localNodeId = nodeDiscoveryPort.getLocalNodeId();
        
        // Group containers by service name and build service DTOs with replicas
        var services = buildServiceRegistrations(containers, localNodeId);
        
        // Build host list (no services attached)
        var hosts = nodeDiscoveryPort.discoverNodes().stream()
                .map(this::toHostDto)
                .toList();

        viplevApiPort.registerServices(new ServiceRegistrationDTO()
                .services(services)
                .hosts(hosts));
        log.info("Registered {} service(s) with {} total replica(s) on local node, {} host(s) total with VIPLEV",
                services.size(), containers.size(), hosts.size());
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

    private List<ServiceRegistrationServiceDTO> buildServiceRegistrations(
            List<ContainerInfo> containers, String localNodeId) {
        
        // Group containers by service name
        Map<String, List<ContainerInfo>> serviceGroups = new HashMap<>();
        for (ContainerInfo container : containers) {
            String serviceName = container.serviceName() != null 
                    ? container.serviceName() 
                    : container.name();
            serviceGroups.computeIfAbsent(serviceName, k -> new ArrayList<>()).add(container);
        }
        
        // Build ServiceRegistrationServiceDTO for each service group
        List<ServiceRegistrationServiceDTO> services = new ArrayList<>();
        for (Map.Entry<String, List<ContainerInfo>> entry : serviceGroups.entrySet()) {
            String serviceName = entry.getKey();
            List<ContainerInfo> replicas = entry.getValue();
            
            // Use first container's metadata for service-level fields
            ContainerInfo firstContainer = replicas.get(0);
            
            var serviceDto = new ServiceRegistrationServiceDTO()
                    .serviceName(serviceName)
                    .imageSha(firstContainer.imageSha())
                    .imageName(firstContainer.imageName())
                    .cpuLimit(firstContainer.cpuLimit() != null && firstContainer.cpuLimit() > 0
                            ? firstContainer.cpuLimit() / NANO_CPUS_PER_CORE : null)
                    .cpuReservation(firstContainer.cpuReservation() != null && firstContainer.cpuReservation() > 0
                            ? firstContainer.cpuReservation() / CPU_SHARES_PER_CORE : null)
                    .memoryLimitBytes(firstContainer.memoryLimit())
                    .memoryReservationBytes(firstContainer.memoryReservation())
                    .replicas(replicas.stream()
                            .map(container -> toReplicaDto(container, localNodeId))
                            .toList());
            
            services.add(serviceDto);
        }
        
        return services;
    }

    private ServiceReplicaDTO toReplicaDto(ContainerInfo container, String machineId) {
        return new ServiceReplicaDTO()
                .containerId(container.id())
                .containerName(container.name())
                .machineId(machineId)
                .startedAt(container.startedAt());
    }
}
