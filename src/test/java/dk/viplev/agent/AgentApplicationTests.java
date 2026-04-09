package dk.viplev.agent;

import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.discovery.NodeDiscoveryPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AgentApplicationTests {

	@MockitoBean
	private ContainerPort containerPort;

	@MockitoBean
	private NodeDiscoveryPort nodeDiscoveryPort;

	@MockitoBean
	private ViplevApiPort viplevApiPort;

	@Test
	void contextLoads() {
	}

}
