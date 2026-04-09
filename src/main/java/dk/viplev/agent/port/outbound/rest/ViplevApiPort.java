package dk.viplev.agent.port.outbound.rest;

import dk.viplev.agent.generated.model.BenchmarkRunStatusUpdateDTO;
import dk.viplev.agent.generated.model.BenchmarkDTO;
import dk.viplev.agent.generated.model.MessageDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.generated.model.MetricResourceDTO;
import dk.viplev.agent.generated.model.ServiceRegistrationDTO;

import java.util.List;
import java.util.UUID;

public interface ViplevApiPort {

    List<MessageDTO> pollMessages();

    BenchmarkDTO getBenchmark(UUID benchmarkId);

    void registerServices(ServiceRegistrationDTO registration);

    void updateRunStatus(UUID benchmarkId, UUID runId, BenchmarkRunStatusUpdateDTO status);

    void sendResourceMetrics(UUID benchmarkId, UUID runId, MetricResourceDTO metrics);

    void sendPerformanceMetrics(UUID benchmarkId, UUID runId, MetricPerformanceDTO metrics);
}
