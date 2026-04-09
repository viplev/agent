package dk.viplev.agent.config;

import dk.viplev.agent.generated.api.AgentApi;
import dk.viplev.agent.generated.api.BenchmarkApi;
import dk.viplev.agent.generated.invoker.ApiClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.UUID;

@Configuration
public class ViplevApiConfig {

    @Bean
    public ApiClient viplevApiClient(@Value("${viplev.url}") String baseUrl,
                                     @Value("${viplev.token}") String token) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "Configuration property 'viplev.url' (env: VIPLEV_URL) "
                            + "must be set to a non-empty URL.");
        }
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException(
                    "Configuration property 'viplev.token' (env: VIPLEV_TOKEN) "
                            + "must be set to a non-empty bearer token.");
        }
        ApiClient client = new ApiClient();
        client.setBasePath(baseUrl);
        client.setBearerToken(token);
        return client;
    }

    @Bean
    public AgentApi agentApi(ApiClient apiClient) {
        return new AgentApi(apiClient);
    }

    @Bean
    public BenchmarkApi benchmarkApi(ApiClient apiClient) {
        return new BenchmarkApi(apiClient);
    }

    @Bean
    public UUID viplevEnvironmentId(@Value("${viplev.environment-id}") String environmentId) {
        if (environmentId == null || environmentId.isBlank()) {
            throw new IllegalArgumentException(
                    "Configuration property 'viplev.environment-id' (env: VIPLEV_ENVIRONMENT_ID) "
                            + "must be set to a non-empty UUID string.");
        }
        try {
            return UUID.fromString(environmentId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Configuration property 'viplev.environment-id' (env: VIPLEV_ENVIRONMENT_ID) "
                            + "must be a valid UUID, but was: '" + environmentId + "'.", e);
        }
    }
}
