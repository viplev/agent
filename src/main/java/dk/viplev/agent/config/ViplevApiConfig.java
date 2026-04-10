package dk.viplev.agent.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import dk.viplev.agent.generated.api.AgentApi;
import dk.viplev.agent.generated.invoker.ApiClient;
import org.openapitools.jackson.nullable.JsonNullableModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
        RestTemplate restTemplate = buildRestTemplateWithFlexibleDateParsing();
        ApiClient client = new ApiClient(restTemplate);
        client.setBasePath(baseUrl);
        client.setBearerToken(token);
        return client;
    }

    private RestTemplate buildRestTemplateWithFlexibleDateParsing() {
        RestTemplate restTemplate = new RestTemplate();

        for (HttpMessageConverter<?> converter : restTemplate.getMessageConverters()) {
            if (!(converter instanceof MappingJackson2HttpMessageConverter jsonConverter)) {
                continue;
            }

            ObjectMapper objectMapper = jsonConverter.getObjectMapper().copy();
            objectMapper.registerModule(new JsonNullableModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            SimpleModule flexibleDateModule = new SimpleModule();
            flexibleDateModule.addDeserializer(OffsetDateTime.class, new FlexibleOffsetDateTimeDeserializer());
            flexibleDateModule.addDeserializer(LocalDateTime.class, new FlexibleLocalDateTimeDeserializer());
            objectMapper.registerModule(flexibleDateModule);

            jsonConverter.setObjectMapper(objectMapper);
        }

        return restTemplate;
    }

    @Bean
    public AgentApi agentApi(ApiClient apiClient) {
        return new AgentApi(apiClient);
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

    private static final class FlexibleOffsetDateTimeDeserializer extends JsonDeserializer<OffsetDateTime> {
        @Override
        public OffsetDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String value = parser.getValueAsString();
            if (value == null || value.isBlank()) {
                return null;
            }

            try {
                return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            } catch (Exception ignored) {
                LocalDateTime localDateTime = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                return localDateTime.atOffset(ZoneOffset.UTC);
            }
        }
    }

    private static final class FlexibleLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            String value = parser.getValueAsString();
            if (value == null || value.isBlank()) {
                return null;
            }

            try {
                return LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (Exception ignored) {
                OffsetDateTime offsetDateTime = OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return offsetDateTime.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
            }
        }
    }
}
