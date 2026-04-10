package dk.viplev.agent.config;

import dk.viplev.agent.generated.api.AgentApi;
import dk.viplev.agent.generated.invoker.ApiClient;
import dk.viplev.agent.generated.invoker.auth.HttpBearerAuth;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ViplevApiConfigTest {

    private final ViplevApiConfig config = new ViplevApiConfig();

    @Test
    void viplevApiClient_setsBasePathAndBearerToken() {
        ApiClient client = config.viplevApiClient("https://viplev.example.com", "test-token");

        assertThat(client.getBasePath()).isEqualTo("https://viplev.example.com");
        var bearerAuth = (HttpBearerAuth) client.getAuthentications().get("bearerAuth");
        assertThat(bearerAuth.getBearerToken()).isEqualTo("test-token");
    }

    @Test
    void viplevApiClient_jacksonSupportsFlexibleLocalDateAndOffsetDateParsing() throws Exception {
        RestTemplate restTemplate = extractRestTemplate(config.viplevApiClient("https://viplev.example.com", "test-token"));
        MappingJackson2HttpMessageConverter jsonConverter = null;
        for (HttpMessageConverter<?> converter : restTemplate.getMessageConverters()) {
            if (converter instanceof MappingJackson2HttpMessageConverter mappingJackson2HttpMessageConverter) {
                jsonConverter = mappingJackson2HttpMessageConverter;
                break;
            }
        }

        assertThat(jsonConverter).isNotNull();
        var mapper = jsonConverter.getObjectMapper();

        var localFromLocal = mapper.readValue("\"2026-04-10T08:26:33.431800541\"", LocalDateTime.class);
        var localFromOffset = mapper.readValue("\"2026-04-10T08:26:33.431800541Z\"", LocalDateTime.class);
        var offsetFromOffset = mapper.readValue("\"2026-04-10T08:26:33.431800541Z\"", OffsetDateTime.class);
        var offsetFromLocal = mapper.readValue("\"2026-04-10T08:26:33.431800541\"", OffsetDateTime.class);

        assertThat(localFromLocal).isEqualTo(LocalDateTime.parse("2026-04-10T08:26:33.431800541"));
        assertThat(localFromOffset).isEqualTo(LocalDateTime.parse("2026-04-10T08:26:33.431800541"));
        assertThat(offsetFromOffset).isEqualTo(OffsetDateTime.parse("2026-04-10T08:26:33.431800541Z"));
        assertThat(offsetFromLocal).isEqualTo(OffsetDateTime.parse("2026-04-10T08:26:33.431800541Z"));
    }

    private RestTemplate extractRestTemplate(ApiClient client) {
        try {
            var field = ApiClient.class.getDeclaredField("restTemplate");
            field.setAccessible(true);
            return (RestTemplate) field.get(client);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to extract RestTemplate from ApiClient", e);
        }
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
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("viplev.environment-id");
    }

    @Test
    void viplevEnvironmentId_throwsOnBlank() {
        assertThatThrownBy(() -> config.viplevEnvironmentId("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("viplev.environment-id");
    }

    @Test
    void viplevApiClient_throwsOnBlankToken() {
        assertThatThrownBy(() -> config.viplevApiClient("https://viplev.example.com", "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("viplev.token");
    }

    @Test
    void viplevApiClient_throwsOnBlankUrl() {
        assertThatThrownBy(() -> config.viplevApiClient("  ", "test-token"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("viplev.url");
    }
}
