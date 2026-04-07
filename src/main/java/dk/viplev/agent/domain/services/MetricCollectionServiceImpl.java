package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.mapper.ResourceMetricMapper;
import dk.viplev.agent.domain.model.ResourceMetric;
import dk.viplev.agent.domain.model.TargetType;
import dk.viplev.agent.generated.model.MetricResourceDTO;
import dk.viplev.agent.generated.model.MetricResourceNodeDTO;
import dk.viplev.agent.generated.model.MetricResourceServiceDTO;
import dk.viplev.agent.port.inbound.MetricCollectionUseCase;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.db.ResourceMetricRepository;
import dk.viplev.agent.port.outbound.discovery.NodeDiscoveryPort;
import dk.viplev.agent.port.outbound.metrics.CadvisorPort;
import dk.viplev.agent.port.outbound.metrics.NodeExporterPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@Profile("docker")
public class MetricCollectionServiceImpl implements MetricCollectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(MetricCollectionServiceImpl.class);
    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);

    private final NodeExporterPort nodeExporterPort;
    private final CadvisorPort cadvisorPort;
    private final ContainerPort containerPort;
    private final NodeDiscoveryPort nodeDiscoveryPort;
    private final ResourceMetricRepository resourceMetricRepository;
    private final ViplevApiPort viplevApiPort;
    private final ResourceMetricMapper resourceMetricMapper;
    private final int nodeExporterPortNumber;
    private final int cadvisorPortNumber;
    @Nullable
    private final ScheduledExecutorService executorOverride;

    private volatile UUID benchmarkId;
    private volatile UUID runId;
    private volatile ScheduledExecutorService executor;
    private volatile String localMachineId;

    public MetricCollectionServiceImpl(NodeExporterPort nodeExporterPort,
                                       CadvisorPort cadvisorPort,
                                       ContainerPort containerPort,
                                       NodeDiscoveryPort nodeDiscoveryPort,
                                       ResourceMetricRepository resourceMetricRepository,
                                       ViplevApiPort viplevApiPort,
                                       ResourceMetricMapper resourceMetricMapper,
                                       @Value("${agent.node-exporter-port}") int nodeExporterPortNumber,
                                       @Value("${agent.cadvisor-port}") int cadvisorPortNumber,
                                       @Nullable ScheduledExecutorService executorOverride) {
        this.nodeExporterPort = nodeExporterPort;
        this.cadvisorPort = cadvisorPort;
        this.containerPort = containerPort;
        this.nodeDiscoveryPort = nodeDiscoveryPort;
        this.resourceMetricRepository = resourceMetricRepository;
        this.viplevApiPort = viplevApiPort;
        this.resourceMetricMapper = resourceMetricMapper;
        this.nodeExporterPortNumber = nodeExporterPortNumber;
        this.cadvisorPortNumber = cadvisorPortNumber;
        this.executorOverride = executorOverride;
    }

    @Override
    public synchronized void startCollection(UUID benchmarkId, UUID runId) {
        if (executor != null && !executor.isShutdown()) {
            log.warn("Metric collection already running for benchmark={} run={}; ignoring startCollection request",
                    this.benchmarkId, this.runId);
            return;
        }

        this.benchmarkId = benchmarkId;
        this.runId = runId;

        if (executorOverride != null) {
            executor = executorOverride;
        } else {
            executor = Executors.newScheduledThreadPool(2, r -> {
                Thread t = new Thread(r, "metric-collector-" + THREAD_COUNTER.getAndIncrement());
                t.setDaemon(true);
                return t;
            });
        }

        executor.scheduleAtFixedRate(this::collectMetrics, 0, 1, TimeUnit.SECONDS);
        executor.scheduleAtFixedRate(this::flushMetrics, 5, 5, TimeUnit.SECONDS);

        log.info("Metric collection started for benchmark={} run={}", benchmarkId, runId);
    }

    @Override
    public synchronized void stopCollection() {
        if (executor == null || executor.isShutdown()) {
            log.warn("Metric collection is not running; ignoring stopCollection request");
            return;
        }

        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        executor = null;

        flushMetrics();

        log.info("Metric collection stopped for benchmark={} run={}", benchmarkId, runId);

        this.benchmarkId = null;
        this.runId = null;
    }

    void collectMetrics() {
        try {
            var nodes = nodeDiscoveryPort.discoverNodes();

            String localId = getLocalMachineId();

            for (var node : nodes) {
                String nodeExporterUrl = "http://" + node.ipAddress() + ":" + nodeExporterPortNumber;
                String cadvisorUrl = "http://" + node.ipAddress() + ":" + cadvisorPortNumber;
                try {
                    var hostStats = nodeExporterPort.scrapeHostStats(nodeExporterUrl);
                    var hostMetric = ResourceMetric.builder()
                            .collectedAt(LocalDateTime.now())
                            .targetType(TargetType.HOST)
                            .targetName(node.machineId())
                            .machineId(node.machineId())
                            .cpuPercentage(hostStats.cpuPercentage())
                            .memoryUsageBytes((double) hostStats.memoryUsageBytes())
                            .memoryLimitBytes((double) hostStats.memoryLimitBytes())
                            .networkInBytes((double) hostStats.networkInBytes())
                            .networkOutBytes((double) hostStats.networkOutBytes())
                            .blockInBytes((double) hostStats.blockInBytes())
                            .blockOutBytes((double) hostStats.blockOutBytes())
                            .build();
                    resourceMetricRepository.save(hostMetric);
                } catch (Exception e) {
                    log.warn("Failed to collect host stats for node={}", node.machineId(), e);
                }

                try {
                    var containerStatsMap = cadvisorPort.scrapeAllContainerStats(cadvisorUrl);

                    Map<String, String> idToName;
                    if (node.machineId().equals(localId)) {
                        var containers = containerPort.listContainers();
                        idToName = containers.stream()
                                .collect(Collectors.toMap(c -> c.id(), c -> c.name()));
                    } else {
                        idToName = Map.of();
                    }

                    for (var entry : containerStatsMap.entrySet()) {
                        var containerId = entry.getKey();
                        var stats = entry.getValue();
                        var containerName = idToName.getOrDefault(containerId, containerId);

                        var containerMetric = ResourceMetric.builder()
                                .collectedAt(LocalDateTime.now())
                                .targetType(TargetType.SERVICE)
                                .targetName(containerName)
                                .machineId(node.machineId())
                                .cpuPercentage(stats.cpuPercentage())
                                .memoryUsageBytes((double) stats.memoryUsageBytes())
                                .memoryLimitBytes((double) stats.memoryLimitBytes())
                                .networkInBytes((double) stats.networkInBytes())
                                .networkOutBytes((double) stats.networkOutBytes())
                                .blockInBytes((double) stats.blockInBytes())
                                .blockOutBytes((double) stats.blockOutBytes())
                                .build();
                        resourceMetricRepository.save(containerMetric);
                    }
                } catch (Exception e) {
                    log.warn("Failed to collect container stats for node={}", node.machineId(), e);
                }
            }
        } catch (Exception e) {
            log.warn("Unexpected error in collectMetrics", e);
        }
    }

    void flushMetrics() {
        try {
            var unflushed = resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc();
            if (unflushed.isEmpty()) {
                return;
            }

            if (benchmarkId == null || runId == null) {
                log.warn("flushMetrics called before startCollection (benchmarkId={}, runId={}); skipping flush",
                        benchmarkId, runId);
                return;
            }

            // Filter out legacy metrics with null machineId
            var nullMachineIdMetrics = unflushed.stream()
                    .filter(m -> m.getMachineId() == null)
                    .toList();
            if (!nullMachineIdMetrics.isEmpty()) {
                log.warn("Found {} metrics with null machineId; marking as flushed to expire legacy rows",
                        nullMachineIdMetrics.size());
                nullMachineIdMetrics.forEach(m -> m.setFlushed(true));
                resourceMetricRepository.saveAll(nullMachineIdMetrics);
            }

            unflushed = unflushed.stream()
                    .filter(m -> m.getMachineId() != null)
                    .toList();
            if (unflushed.isEmpty()) {
                return;
            }

            // Group host metrics by machineId
            var hostMetrics = unflushed.stream()
                    .filter(m -> m.getTargetType() == TargetType.HOST)
                    .collect(Collectors.groupingBy(ResourceMetric::getMachineId, LinkedHashMap::new, Collectors.toList()));

            // Group service metrics first by machineId, then by targetName
            var serviceMetrics = unflushed.stream()
                    .filter(m -> m.getTargetType() == TargetType.SERVICE)
                    .collect(Collectors.groupingBy(
                            ResourceMetric::getMachineId,
                            LinkedHashMap::new,
                            Collectors.groupingBy(ResourceMetric::getTargetName, LinkedHashMap::new, Collectors.toList())));

            List<MetricResourceNodeDTO> nodeDTOs = new ArrayList<>();

            // Build node DTOs from host metrics, attaching only services with the same machineId
            for (var entry : hostMetrics.entrySet()) {
                String machineId = entry.getKey();
                Map<String, List<ResourceMetric>> servicesForNode = serviceMetrics.getOrDefault(machineId, new LinkedHashMap<>());

                List<MetricResourceServiceDTO> serviceDTOs = servicesForNode.entrySet().stream()
                        .map(e -> new MetricResourceServiceDTO()
                                .serviceName(e.getKey())
                                .metrics(resourceMetricMapper.toDataPoints(e.getValue())))
                        .toList();

                var nodeDTO = new MetricResourceNodeDTO()
                        .machineId(machineId)
                        .metrics(resourceMetricMapper.toDataPoints(entry.getValue()))
                        .services(serviceDTOs);
                nodeDTOs.add(nodeDTO);
            }

            // For service metrics whose machineId has no corresponding host metric, create orphan node DTOs
            for (var entry : serviceMetrics.entrySet()) {
                String machineId = entry.getKey();
                if (hostMetrics.containsKey(machineId)) {
                    continue;
                }
                List<MetricResourceServiceDTO> serviceDTOs = entry.getValue().entrySet().stream()
                        .map(e -> new MetricResourceServiceDTO()
                                .serviceName(e.getKey())
                                .metrics(resourceMetricMapper.toDataPoints(e.getValue())))
                        .toList();

                var nodeDTO = new MetricResourceNodeDTO()
                        .machineId(machineId)
                        .metrics(List.of())
                        .services(serviceDTOs);
                nodeDTOs.add(nodeDTO);
            }

            if (nodeDTOs.isEmpty()) {
                log.warn("No metrics to flush despite unflushed records; skipping flush");
                return;
            }

            var dto = new MetricResourceDTO().hosts(nodeDTOs);

            try {
                viplevApiPort.sendResourceMetrics(benchmarkId, runId, dto);
                unflushed.forEach(m -> m.setFlushed(true));
                resourceMetricRepository.saveAll(unflushed);
                log.debug("Flushed {} metrics to VIPLEV", unflushed.size());
            } catch (Exception e) {
                log.warn("Failed to send resource metrics to VIPLEV; will retry on next flush", e);
            }
        } catch (Exception e) {
            log.warn("Unexpected error in flushMetrics", e);
        }
    }

    private String getLocalMachineId() {
        if (localMachineId == null) {
            localMachineId = nodeDiscoveryPort.getLocalNodeId();
        }
        return localMachineId;
    }
}
