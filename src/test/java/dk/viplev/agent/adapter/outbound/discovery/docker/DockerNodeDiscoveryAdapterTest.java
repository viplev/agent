package dk.viplev.agent.adapter.outbound.discovery.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InfoCmd;
import com.github.dockerjava.api.command.ListSwarmNodesCmd;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.SwarmNode;
import com.github.dockerjava.api.model.SwarmNodeDescription;
import com.github.dockerjava.api.model.SwarmNodeResources;
import com.github.dockerjava.api.model.SwarmNodeStatus;
import com.github.dockerjava.api.model.LocalNodeState;
import com.github.dockerjava.api.model.SwarmInfo;
import com.github.dockerjava.api.model.SwarmSpec;
import dk.viplev.agent.domain.model.NodeInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerNodeDiscoveryAdapterTest {

    @Mock
    private DockerClient dockerClient;

    @Spy
    private DockerNodeDiscoveryAdapter adapter = new DockerNodeDiscoveryAdapter(null);

    @BeforeEach
    void setUp() {
        adapter = new DockerNodeDiscoveryAdapter(dockerClient);
        adapter = org.mockito.Mockito.spy(adapter);
        lenient().doReturn("192.168.1.10").when(adapter).resolveLocalIpAddress();
    }

    @Test
    void discoverNodes_standaloneMode_returnsSingleNode() {
        mockInfoCmd("daemon-id-abc123", "my-host", "Ubuntu 22.04", "5.15.0", 8, 16_000_000_000L, null);

        List<NodeInfo> nodes = adapter.discoverNodes();

        assertThat(nodes).hasSize(1);
        NodeInfo node = nodes.getFirst();
        assertThat(node.machineId()).isEqualTo("daemon-id-abc123");
        assertThat(node.hostname()).isEqualTo("my-host");
        assertThat(node.ipAddress()).isEqualTo("192.168.1.10");
        assertThat(node.os()).isEqualTo("Ubuntu 22.04");
        assertThat(node.osVersion()).isEqualTo("5.15.0");
        assertThat(node.logicalCpuCount()).isEqualTo(8);
        assertThat(node.ramTotalBytes()).isEqualTo(16_000_000_000L);
    }

    @Test
    void discoverNodes_swarmMode_returnsAllNodes() {
        mockInfoCmdSwarmOnly("active");

        SwarmNode node1 = buildSwarmNode("swarm-node-1", "worker-1", "10.0.0.1", "linux", 4_000_000_000L, 8_000_000_000L);
        SwarmNode node2 = buildSwarmNode("swarm-node-2", "worker-2", "10.0.0.2", "linux", 8_000_000_000L, 16_000_000_000L);

        var listSwarmNodesCmd = mock(ListSwarmNodesCmd.class);
        when(dockerClient.listSwarmNodesCmd()).thenReturn(listSwarmNodesCmd);
        when(listSwarmNodesCmd.exec()).thenReturn(List.of(node1, node2));

        List<NodeInfo> nodes = adapter.discoverNodes();

        assertThat(nodes).hasSize(2);

        NodeInfo first = nodes.get(0);
        assertThat(first.machineId()).isEqualTo("swarm-node-1");
        assertThat(first.hostname()).isEqualTo("worker-1");
        assertThat(first.ipAddress()).isEqualTo("10.0.0.1");
        assertThat(first.os()).isEqualTo("linux");
        assertThat(first.logicalCpuCount()).isEqualTo(4);
        assertThat(first.ramTotalBytes()).isEqualTo(8_000_000_000L);

        NodeInfo second = nodes.get(1);
        assertThat(second.machineId()).isEqualTo("swarm-node-2");
        assertThat(second.logicalCpuCount()).isEqualTo(8);
    }

    @Test
    void discoverNodes_swarmMode_usesNodeIpFromStatus() {
        mockInfoCmdSwarmOnly("active");

        SwarmNode node = buildSwarmNode("node-id", "worker", "172.20.0.5", "linux", 2_000_000_000L, 4_000_000_000L);
        var listSwarmNodesCmd = mock(ListSwarmNodesCmd.class);
        when(dockerClient.listSwarmNodesCmd()).thenReturn(listSwarmNodesCmd);
        when(listSwarmNodesCmd.exec()).thenReturn(List.of(node));

        List<NodeInfo> nodes = adapter.discoverNodes();

        // IP comes from node status, not NetworkInterface
        assertThat(nodes.getFirst().ipAddress()).isEqualTo("172.20.0.5");
    }

    @Test
    void getLocalNodeId_standaloneMode_returnsDaemonId() {
        var infoCmd = mock(InfoCmd.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
        var info = mock(Info.class);
        when(infoCmd.exec()).thenReturn(info);
        when(info.getId()).thenReturn("daemon-id-abc123");
        when(info.getSwarm()).thenReturn(null);

        assertThat(adapter.getLocalNodeId()).isEqualTo("daemon-id-abc123");
    }

    @Test
    void getLocalNodeId_swarmMode_returnsSwarmNodeId() {
        var infoCmd = mock(InfoCmd.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
        var info = mock(Info.class);
        when(infoCmd.exec()).thenReturn(info);
        var swarmInfo = mock(SwarmInfo.class);
        when(info.getSwarm()).thenReturn(swarmInfo);
        when(swarmInfo.getLocalNodeState()).thenReturn(LocalNodeState.ACTIVE);
        when(swarmInfo.getNodeID()).thenReturn("swarm-node-id-xyz");

        assertThat(adapter.getLocalNodeId()).isEqualTo("swarm-node-id-xyz");
    }

    // -- Helpers --

    private void mockInfoCmd(String id, String name, String os, String kernelVersion,
                              int ncpu, long memTotal, String swarmLocalNodeState) {
        var infoCmd = mock(InfoCmd.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);

        var info = mock(Info.class);
        when(infoCmd.exec()).thenReturn(info);
        when(info.getId()).thenReturn(id);
        when(info.getName()).thenReturn(name);
        when(info.getOperatingSystem()).thenReturn(os);
        when(info.getKernelVersion()).thenReturn(kernelVersion);
        when(info.getNCPU()).thenReturn(ncpu);
        when(info.getMemTotal()).thenReturn(memTotal);

        if (swarmLocalNodeState != null) {
            var swarmInfo = mock(SwarmInfo.class);
            when(info.getSwarm()).thenReturn(swarmInfo);
            when(swarmInfo.getLocalNodeState()).thenReturn(LocalNodeState.forValue(swarmLocalNodeState));
        } else {
            when(info.getSwarm()).thenReturn(null);
        }
    }

    private void mockInfoCmdSwarmOnly(String swarmLocalNodeState) {
        var infoCmd = mock(InfoCmd.class);
        when(dockerClient.infoCmd()).thenReturn(infoCmd);
        var info = mock(Info.class);
        when(infoCmd.exec()).thenReturn(info);
        var swarmInfo = mock(SwarmInfo.class);
        when(info.getSwarm()).thenReturn(swarmInfo);
        when(swarmInfo.getLocalNodeState()).thenReturn(LocalNodeState.forValue(swarmLocalNodeState));
    }

    private SwarmNode buildSwarmNode(String nodeId, String hostname, String ipAddress,
                                     String os, long nanoCPUs, long memoryBytes) {
        var node = mock(SwarmNode.class);
        when(node.getId()).thenReturn(nodeId);

        var description = mock(SwarmNodeDescription.class);
        when(node.getDescription()).thenReturn(description);
        when(description.getHostname()).thenReturn(hostname);

        var platform = mock(com.github.dockerjava.api.model.SwarmNodePlatform.class);
        when(description.getPlatform()).thenReturn(platform);
        when(platform.getOs()).thenReturn(os);

        var resources = mock(SwarmNodeResources.class);
        when(description.getResources()).thenReturn(resources);
        when(resources.getNanoCPUs()).thenReturn(nanoCPUs);
        when(resources.getMemoryBytes()).thenReturn(memoryBytes);

        var status = mock(SwarmNodeStatus.class);
        when(node.getStatus()).thenReturn(status);
        when(status.getAddress()).thenReturn(ipAddress);

        return node;
    }
}
