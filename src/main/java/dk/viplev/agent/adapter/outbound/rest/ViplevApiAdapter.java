package dk.viplev.agent.adapter.outbound.rest;

import dk.viplev.agent.domain.exception.ViplevApiException;
import dk.viplev.agent.generated.api.AgentApi;
import dk.viplev.agent.generated.model.BenchmarkRunStatusUpdateDTO;
import dk.viplev.agent.generated.model.MessageDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.generated.model.MetricResourceDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class ViplevApiAdapter implements ViplevApiPort {

    private final AgentApi agentApi;
    private final UUID viplevEnvironmentId;

    @Override
    public List<MessageDTO> pollMessages() {
        log.debug("Polling messages for environment {}", viplevEnvironmentId);
        try {
            return agentApi.listMessages(viplevEnvironmentId);
        } catch (HttpStatusCodeException e) {
            throw new ViplevApiException("Failed to poll messages: " + e.getStatusText(),
                    e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ViplevApiException("Failed to poll messages: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public void registerServices(ServiceRegistrationDTO registration) {
        log.debug("Registering services for environment {}", viplevEnvironmentId);
        try {
            agentApi.registerServices(viplevEnvironmentId, registration);
        } catch (HttpStatusCodeException e) {
            throw new ViplevApiException("Failed to register services: " + e.getStatusText(),
                    e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ViplevApiException("Failed to register services: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public void updateRunStatus(UUID benchmarkId, UUID runId, BenchmarkRunStatusUpdateDTO status) {
        log.debug("Updating run status for benchmark {} run {} in environment {}",
                benchmarkId, runId, viplevEnvironmentId);
        try {
            agentApi.updateBenchmarkRunStatus(viplevEnvironmentId, benchmarkId, runId, status);
        } catch (HttpStatusCodeException e) {
            throw new ViplevApiException("Failed to update run status: " + e.getStatusText(),
                    e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ViplevApiException("Failed to update run status: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public void sendResourceMetrics(UUID benchmarkId, UUID runId, MetricResourceDTO metrics) {
        log.debug("Sending resource metrics for benchmark {} run {} in environment {}",
                benchmarkId, runId, viplevEnvironmentId);
        try {
            agentApi.storeResourceMetrics(viplevEnvironmentId, benchmarkId, runId, metrics);
        } catch (HttpStatusCodeException e) {
            throw new ViplevApiException("Failed to send resource metrics: " + e.getStatusText(),
                    e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ViplevApiException("Failed to send resource metrics: " + e.getMessage(), 0, e);
        }
    }

    @Override
    public void sendPerformanceMetrics(UUID benchmarkId, UUID runId, MetricPerformanceDTO metrics) {
        log.debug("Sending performance metrics for benchmark {} run {} in environment {}",
                benchmarkId, runId, viplevEnvironmentId);
        try {
            agentApi.storePerformanceMetrics(viplevEnvironmentId, benchmarkId, runId, metrics);
        } catch (HttpStatusCodeException e) {
            throw new ViplevApiException("Failed to send performance metrics: " + e.getStatusText(),
                    e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            throw new ViplevApiException("Failed to send performance metrics: " + e.getMessage(), 0, e);
        }
    }
}
