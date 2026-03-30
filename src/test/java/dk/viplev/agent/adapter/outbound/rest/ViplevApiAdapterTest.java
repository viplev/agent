package dk.viplev.agent.adapter.outbound.rest;

import dk.viplev.agent.domain.exception.ViplevApiException;
import dk.viplev.agent.generated.api.AgentApi;
import dk.viplev.agent.generated.model.BenchmarkRunStatusUpdateDTO;
import dk.viplev.agent.generated.model.MessageDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.generated.model.MetricResourceDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ViplevApiAdapterTest {

    @Mock
    private AgentApi agentApi;

    private final UUID environmentId = UUID.randomUUID();
    private final UUID benchmarkId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    private ViplevApiAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ViplevApiAdapter(agentApi, environmentId);
    }

    @Test
    void pollMessages_delegatesToAgentApi() {
        var expected = List.of(new MessageDTO());
        when(agentApi.listMessages(environmentId)).thenReturn(expected);

        var result = adapter.pollMessages();

        assertThat(result).isEqualTo(expected);
        verify(agentApi).listMessages(environmentId);
    }

    @Test
    void registerServices_delegatesToAgentApi() {
        var registration = new ServiceRegistrationDTO();

        adapter.registerServices(registration);

        verify(agentApi).registerServices(environmentId, registration);
    }

    @Test
    void updateRunStatus_delegatesToAgentApi() {
        var status = new BenchmarkRunStatusUpdateDTO();

        adapter.updateRunStatus(benchmarkId, runId, status);

        verify(agentApi).updateBenchmarkRunStatus(environmentId, benchmarkId, runId, status);
    }

    @Test
    void sendResourceMetrics_delegatesToAgentApi() {
        var metrics = new MetricResourceDTO();

        adapter.sendResourceMetrics(benchmarkId, runId, metrics);

        verify(agentApi).storeResourceMetrics(environmentId, benchmarkId, runId, metrics);
    }

    @Test
    void sendPerformanceMetrics_delegatesToAgentApi() {
        var metrics = new MetricPerformanceDTO();

        adapter.sendPerformanceMetrics(benchmarkId, runId, metrics);

        verify(agentApi).storePerformanceMetrics(environmentId, benchmarkId, runId, metrics);
    }

    @Test
    void pollMessages_wrapsClientError() {
        when(agentApi.listMessages(environmentId))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "Not Found"));

        assertThatThrownBy(() -> adapter.pollMessages())
                .isInstanceOf(ViplevApiException.class)
                .hasFieldOrPropertyWithValue("statusCode", 404)
                .hasMessageContaining("Failed to poll messages");
    }

    @Test
    void registerServices_wrapsServerError() {
        var registration = new ServiceRegistrationDTO();
        doThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Server Error"))
                .when(agentApi).registerServices(environmentId, registration);

        assertThatThrownBy(() -> adapter.registerServices(registration))
                .isInstanceOf(ViplevApiException.class)
                .hasFieldOrPropertyWithValue("statusCode", 500)
                .hasMessageContaining("Failed to register services");
    }

    @Test
    void updateRunStatus_wrapsConnectionError() {
        var status = new BenchmarkRunStatusUpdateDTO();
        when(agentApi.updateBenchmarkRunStatus(environmentId, benchmarkId, runId, status))
                .thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> adapter.updateRunStatus(benchmarkId, runId, status))
                .isInstanceOf(ViplevApiException.class)
                .hasFieldOrPropertyWithValue("statusCode", 0)
                .hasMessageContaining("Failed to update run status");
    }

    @Test
    void sendResourceMetrics_wrapsClientError() {
        var metrics = new MetricResourceDTO();
        doThrow(new HttpClientErrorException(HttpStatus.UNAUTHORIZED, "Unauthorized"))
                .when(agentApi).storeResourceMetrics(environmentId, benchmarkId, runId, metrics);

        assertThatThrownBy(() -> adapter.sendResourceMetrics(benchmarkId, runId, metrics))
                .isInstanceOf(ViplevApiException.class)
                .hasFieldOrPropertyWithValue("statusCode", 401)
                .hasMessageContaining("Failed to send resource metrics");
    }

    @Test
    void sendPerformanceMetrics_wrapsServerError() {
        var metrics = new MetricPerformanceDTO();
        doThrow(new HttpServerErrorException(HttpStatus.BAD_GATEWAY, "Bad Gateway"))
                .when(agentApi).storePerformanceMetrics(environmentId, benchmarkId, runId, metrics);

        assertThatThrownBy(() -> adapter.sendPerformanceMetrics(benchmarkId, runId, metrics))
                .isInstanceOf(ViplevApiException.class)
                .hasFieldOrPropertyWithValue("statusCode", 502)
                .hasMessageContaining("Failed to send performance metrics");
    }
}
