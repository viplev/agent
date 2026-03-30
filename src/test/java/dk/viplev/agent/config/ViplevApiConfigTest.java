package dk.viplev.agent.config;

import dk.viplev.agent.generated.api.AgentApi;
import dk.viplev.agent.generated.invoker.ApiClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViplevApiConfigTest {

    private final ViplevApiConfig config = new ViplevApiConfig();

    @Test
    void viplevApiClient_setsBasePathAndBearerToken() {
        ApiClient client = config.viplevApiClient("https://viplev.example.com", "test-token");

        assertThat(client.getBasePath()).isEqualTo("https://viplev.example.com");
    }

    @Test
    void agentApi_usesProvidedApiClient() {
        ApiClient client = config.viplevApiClient("https://viplev.example.com", "test-token");

        AgentApi agentApi = config.agentApi(client);

        assertThat(agentApi.getApiClient()).isSameAs(client);
    }

    @Test
    void viplevEnvironmentId_parsesValidUuid() {
        String uuid = "550e8400-e29b-41d4-a716-446655440000";

        UUID result = config.viplevEnvironmentId(uuid);

        assertThat(result).isEqualTo(UUID.fromString(uuid));
    }

    @Test
    void viplevEnvironmentId_throwsOnInvalidUuid() {
        assertThatThrownBy(() -> config.viplevEnvironmentId("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
