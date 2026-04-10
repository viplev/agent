package dk.viplev.agent.adapter.outbound.rest;

import dk.viplev.agent.domain.exception.ViplevApiException;
import dk.viplev.agent.generated.api.AgentApi;
import dk.viplev.agent.generated.model.BenchmarkRunStatusUpdateDTO;
import dk.viplev.agent.generated.model.MessageDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.generated.model.MetricResourceDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class ViplevApiAdapter implements ViplevApiPort {

    private static final Logger log = LoggerFactory.getLogger(ViplevApiAdapter.class);

    private final AgentApi agentApi;
    private final UUID viplevEnvironmentId;

    public ViplevApiAdapter(AgentApi agentApi, UUID viplevEnvironmentId) {
        this.agentApi = agentApi;
        this.viplevEnvironmentId = viplevEnvironmentId;
    }

    @Override
    public List<MessageDTO> pollMessages() {
        log.debug("Polling messages for environment {}", viplevEnvironmentId);
        return execute("poll messages", () -> agentApi.listMessages(viplevEnvironmentId));
    }

    @Override
    public void registerServices(ServiceRegistrationDTO registration) {
        log.debug("Registering services for environment {}", viplevEnvironmentId);
        execute("register services", () -> {
            agentApi.registerServices(viplevEnvironmentId, registration);
            return null;
        });
    }

    @Override
    public void updateRunStatus(UUID benchmarkId, UUID runId, BenchmarkRunStatusUpdateDTO status) {
        log.debug("Updating run status for benchmark {} run {} in environment {}",
                benchmarkId, runId, viplevEnvironmentId);
        execute("update run status", () -> {
            agentApi.updateBenchmarkRunStatus(viplevEnvironmentId, benchmarkId, runId, status);
            return null;
        });
    }

    @Override
    public void sendResourceMetrics(UUID benchmarkId, UUID runId, MetricResourceDTO metrics) {
        log.debug("Sending resource metrics for benchmark {} run {} in environment {}",
                benchmarkId, runId, viplevEnvironmentId);
        execute("send resource metrics", () -> {
            agentApi.storeResourceMetrics(viplevEnvironmentId, benchmarkId, runId, metrics);
            return null;
        });
    }

    @Override
    public void sendPerformanceMetrics(UUID benchmarkId, UUID runId, MetricPerformanceDTO metrics) {
        log.debug("Sending performance metrics for benchmark {} run {} in environment {}",
                benchmarkId, runId, viplevEnvironmentId);
        execute("send performance metrics", () -> {
            agentApi.storePerformanceMetrics(viplevEnvironmentId, benchmarkId, runId, metrics);
            return null;
        });
    }

    private <T> T execute(String operation, Supplier<T> action) {
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            throw new ViplevApiException(
                    String.format("Failed to %s: HTTP %d %s — %s",
                            operation, e.getStatusCode().value(), e.getStatusText(),
                            e.getResponseBodyAsString()),
                    e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ViplevApiException("Failed to " + operation + ": " + e.getMessage(), 0, e);
        }
    }
}
