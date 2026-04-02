package dk.viplev.agent;

import dk.viplev.agent.port.outbound.container.ContainerPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class AgentApplicationTests {

	@MockitoBean
	private ContainerPort containerPort;

	@Test
	void contextLoads() {
	}

}
