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
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Profile("docker")
public class MetricCollectionServiceImpl implements MetricCollectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(MetricCollectionServiceImpl.class);

    private final NodeExporterPort nodeExporterPort;
    private final CadvisorPort cadvisorPort;
    private final ContainerPort containerPort;
    private final NodeDiscoveryPort nodeDiscoveryPort;
    private final ResourceMetricRepository resourceMetricRepository;
    private final ViplevApiPort viplevApiPort;
    private final ResourceMetricMapper resourceMetricMapper;

    private volatile UUID benchmarkId;
    private volatile UUID runId;
    private volatile ScheduledExecutorService executor;

    public MetricCollectionServiceImpl(NodeExporterPort nodeExporterPort,
                                       CadvisorPort cadvisorPort,
                                       ContainerPort containerPort,
                                       NodeDiscoveryPort nodeDiscoveryPort,
                                       ResourceMetricRepository resourceMetricRepository,
                                       ViplevApiPort viplevApiPort,
                                       ResourceMetricMapper resourceMetricMapper) {
        this.nodeExporterPort = nodeExporterPort;
        this.cadvisorPort = cadvisorPort;
        this.containerPort = containerPort;
        this.nodeDiscoveryPort = nodeDiscoveryPort;
        this.resourceMetricRepository = resourceMetricRepository;
        this.viplevApiPort = viplevApiPort;
        this.resourceMetricMapper = resourceMetricMapper;
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

        executor = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "metric-collector");
            t.setDaemon(true);
            return t;
        });

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
        executor = null;

        flushMetrics();

        log.info("Metric collection stopped for benchmark={} run={}", benchmarkId, runId);

        this.benchmarkId = null;
        this.runId = null;
    }

    void collectMetrics() {
        try {
            var nodes = nodeDiscoveryPort.discoverNodes();

            for (var node : nodes) {
                String nodeExporterUrl = "http://" + node.ipAddress() + ":9100";
                String cadvisorUrl = "http://" + node.ipAddress() + ":8080";
                try {
                    var hostStats = nodeExporterPort.scrapeHostStats(nodeExporterUrl);
                    var hostMetric = ResourceMetric.builder()
                            .collectedAt(LocalDateTime.now())
                            .targetType(TargetType.HOST)
                            .targetName(node.machineId())
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
                    var containers = containerPort.listContainers();
                    var idToName = containers.stream()
                            .collect(Collectors.toMap(c -> c.id(), c -> c.name()));

                    for (var entry : containerStatsMap.entrySet()) {
                        var containerId = entry.getKey();
                        var stats = entry.getValue();
                        var containerName = idToName.getOrDefault(containerId, containerId);

                        var containerMetric = ResourceMetric.builder()
                                .collectedAt(LocalDateTime.now())
                                .targetType(TargetType.SERVICE)
                                .targetName(containerName)
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

            var hostMetrics = unflushed.stream()
                    .filter(m -> m.getTargetType() == TargetType.HOST)
                    .collect(Collectors.groupingBy(ResourceMetric::getTargetName, LinkedHashMap::new, Collectors.toList()));

            var serviceMetrics = unflushed.stream()
                    .filter(m -> m.getTargetType() == TargetType.SERVICE)
                    .collect(Collectors.groupingBy(ResourceMetric::getTargetName, LinkedHashMap::new, Collectors.toList()));

            List<MetricResourceServiceDTO> serviceDTOs = serviceMetrics.entrySet().stream()
                    .map(e -> new MetricResourceServiceDTO()
                            .serviceName(e.getKey())
                            .metrics(resourceMetricMapper.toDataPoints(e.getValue())))
                    .toList();

            List<MetricResourceNodeDTO> nodeDTOs = new ArrayList<>();
            for (var entry : hostMetrics.entrySet()) {
                var nodeDTO = new MetricResourceNodeDTO()
                        .machineId(entry.getKey())
                        .metrics(resourceMetricMapper.toDataPoints(entry.getValue()))
                        .services(serviceDTOs);
                nodeDTOs.add(nodeDTO);
            }

            if (nodeDTOs.isEmpty() && !serviceDTOs.isEmpty()) {
                log.warn("Service metrics present but no host metrics to attach them to; skipping flush");
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
}
