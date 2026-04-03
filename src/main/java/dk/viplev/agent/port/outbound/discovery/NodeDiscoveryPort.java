package dk.viplev.agent.port.outbound.discovery;

import dk.viplev.agent.domain.model.NodeInfo;

import java.util.List;

public interface NodeDiscoveryPort {

    /** Discover all nodes. Returns 1 entry for standalone Docker, N for Swarm. */
    List<NodeInfo> discoverNodes();
}
