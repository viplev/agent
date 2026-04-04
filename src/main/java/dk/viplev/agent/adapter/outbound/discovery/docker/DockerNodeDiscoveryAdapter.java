package dk.viplev.agent.adapter.outbound.discovery.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.LocalNodeState;
import com.github.dockerjava.api.model.SwarmNode;
import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import dk.viplev.agent.domain.model.NodeInfo;
import dk.viplev.agent.port.outbound.discovery.NodeDiscoveryPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.List;

@Slf4j
@Component
@Profile("docker")
public class DockerNodeDiscoveryAdapter implements NodeDiscoveryPort {

    private final DockerClient dockerClient;

    public DockerNodeDiscoveryAdapter(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public List<NodeInfo> discoverNodes() {
        try {
            var info = dockerClient.infoCmd().exec();
            LocalNodeState localNodeState = info.getSwarm() != null
                    ? info.getSwarm().getLocalNodeState()
                    : null;

            if (LocalNodeState.ACTIVE == localNodeState) {
                log.debug("Swarm mode detected — discovering all Swarm nodes");
                return discoverSwarmNodes();
            } else {
                log.debug("Standalone Docker mode — using daemon info for node identity");
                return List.of(new NodeInfo(
                        info.getId(),
                        info.getName(),
                        resolveLocalIpAddress(),
                        info.getOperatingSystem(),
                        info.getKernelVersion(),
                        info.getNCPU() != null ? info.getNCPU() : 0,
                        info.getMemTotal() != null ? info.getMemTotal() : 0L
                ));
            }
        } catch (DockerException e) {
            throw new ContainerRuntimeException("Failed to discover nodes via Docker API: " + e.getMessage(), e);
        }
    }

    private List<NodeInfo> discoverSwarmNodes() {
        return dockerClient.listSwarmNodesCmd().exec().stream()
                .map(this::toNodeInfo)
                .toList();
    }

    private NodeInfo toNodeInfo(SwarmNode node) {
        var description = node.getDescription();
        var resources = description != null ? description.getResources() : null;
        var platform = description != null ? description.getPlatform() : null;
        var status = node.getStatus();

        String machineId = node.getId();
        String hostname = description != null && description.getHostname() != null ? description.getHostname() : "unknown";
        String ipAddress = status != null && status.getAddress() != null ? status.getAddress() : "unknown";
        String os = platform != null && platform.getOs() != null ? platform.getOs() : "unknown";
        int cpuCores = resources != null && resources.getNanoCPUs() != null
                ? (int) (resources.getNanoCPUs() / 1_000_000_000L) : 0;
        long ramTotalBytes = resources != null && resources.getMemoryBytes() != null
                ? resources.getMemoryBytes() : 0L;

        return new NodeInfo(machineId, hostname, ipAddress, os, "", cpuCores, ramTotalBytes);
    }

    @Override
    public String getLocalNodeId() {
        try {
            var info = dockerClient.infoCmd().exec();
            LocalNodeState localNodeState = info.getSwarm() != null
                    ? info.getSwarm().getLocalNodeState()
                    : null;
            if (LocalNodeState.ACTIVE == localNodeState) {
                return info.getSwarm().getNodeID();
            }
            return info.getId();
        } catch (DockerException e) {
            throw new ContainerRuntimeException("Failed to get local node ID via Docker API: " + e.getMessage(), e);
        }
    }

    String resolveLocalIpAddress() {
        try {
            var networkInterfaces = NetworkInterface.getNetworkInterfaces();
            if (networkInterfaces == null) {
                return "127.0.0.1";
            }
            for (NetworkInterface iface : Collections.list(networkInterfaces)) {
                if (iface.isLoopback() || !iface.isUp()) continue;
                for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                    if (addr.isLoopbackAddress() || addr.isLinkLocalAddress()) continue;
                    if (addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            log.debug("Failed to resolve local IP address via NetworkInterface: {}", e.getMessage());
        }
        return "127.0.0.1";
    }
}
