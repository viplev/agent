package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.mapper.ResourceMetricMapper;
import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.ResourceMetric;
import dk.viplev.agent.domain.model.TargetType;
import dk.viplev.agent.config.ExporterConstants;
import dk.viplev.agent.generated.model.MetricResourceDTO;
import dk.viplev.agent.generated.model.MetricResourceNodeDTO;
import dk.viplev.agent.generated.model.MetricResourceServiceDTO;
import dk.viplev.agent.generated.model.MetricResourceServiceReplicaDTO;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
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
    private final String localNodeExporterHost;
    private final String localCadvisorHost;
    private final boolean clearBufferedMetricsOnStart;
    @Nullable
    private final ScheduledExecutorService executorOverride;
    private final Map<ScraperKey, ScraperHealth> scraperHealthStates = new ConcurrentHashMap<>();
    private final Object metricsDbLock = new Object();

    private volatile UUID benchmarkId;
    private volatile UUID runId;
    private volatile ScheduledExecutorService executor;
    @Nullable
    private volatile ScheduledFuture<?> collectTask;
    @Nullable
    private volatile ScheduledFuture<?> flushTask;
    private volatile String localMachineId;
    private volatile boolean acceptingCollection = true;

    enum ScraperType {
        NODE_EXPORTER("node_exporter"),
        CADVISOR("cadvisor");

        private final String logName;

        ScraperType(String logName) {
            this.logName = logName;
        }
    }

    enum ScraperHealth {
        HEALTHY,
        DEGRADED
    }

    enum ScraperTransition {
        NONE,
        DEGRADED,
        RECOVERED
    }

    enum FlushStatus {
        SENT,
        FAILED,
        SKIPPED
    }

    record FlushResult(FlushStatus status, int metricCount) {
    }

    record ScraperKey(String machineId, ScraperType scraperType) {
    }

    public MetricCollectionServiceImpl(NodeExporterPort nodeExporterPort,
                                       CadvisorPort cadvisorPort,
                                       ContainerPort containerPort,
                                       NodeDiscoveryPort nodeDiscoveryPort,
                                       ResourceMetricRepository resourceMetricRepository,
                                       ViplevApiPort viplevApiPort,
                                       ResourceMetricMapper resourceMetricMapper,
                                       @Value("${agent.node-exporter-port}") int nodeExporterPortNumber,
                                       @Value("${agent.cadvisor-port}") int cadvisorPortNumber,
                                       @Value("${agent.node-exporter-container-name:" + ExporterConstants.NODE_EXPORTER_CONTAINER_NAME + "}") String localNodeExporterHost,
                                       @Value("${agent.cadvisor-container-name:" + ExporterConstants.CADVISOR_CONTAINER_NAME + "}") String localCadvisorHost,
                                       @Value("${agent.clear-buffered-metrics-on-start:true}") boolean clearBufferedMetricsOnStart,
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
        this.localNodeExporterHost = localNodeExporterHost;
        this.localCadvisorHost = localCadvisorHost;
        this.clearBufferedMetricsOnStart = clearBufferedMetricsOnStart;
        this.executorOverride = executorOverride;
    }

    @Override
    public synchronized boolean startCollection(UUID benchmarkId, UUID runId) {
        if (executor != null && !executor.isShutdown()) {
            log.warn("Metric collection already running for benchmark={} run={}; ignoring startCollection request",
                    this.benchmarkId, this.runId);
            return false;
        }

        if (clearBufferedMetricsOnStart) {
            clearBufferedMetrics("starting new collection");
        } else {
            log.info("Preserving buffered resource metrics on start because agent.clear-buffered-metrics-on-start=false");
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

        acceptingCollection = true;
        collectTask = executor.scheduleAtFixedRate(this::collectMetrics, 0, 1, TimeUnit.SECONDS);
        flushTask = executor.scheduleAtFixedRate(this::flushMetrics, 5, 5, TimeUnit.SECONDS);

        log.info("Metric collection started for benchmark={} run={}", benchmarkId, runId);
        return true;
    }

    @Override
    public synchronized boolean stopCollection() {
        if (executor == null || executor.isShutdown()) {
            log.warn("Metric collection is not running; ignoring stopCollection request");
            return false;
        }

        acceptingCollection = false;
        cancelScheduledTask(collectTask);
        cancelScheduledTask(flushTask);
        collectTask = null;
        flushTask = null;

        executor.shutdown();
        boolean terminated = false;
        try {
            terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            if (!terminated) {
                executor.shutdownNow();
                terminated = executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        if (!terminated) {
            log.warn("Metric collection executor did not terminate cleanly before final flush/cleanup");
        }

        executor = null;

        FlushResult flushResult = flushMetrics(false);
        if (flushResult.status == FlushStatus.FAILED && flushResult.metricCount > 0) {
            log.warn("Discarding {} unsent resource metrics while stopping collection", flushResult.metricCount);
        }
        clearBufferedMetrics("stopping collection");

        log.info("Metric collection stopped for benchmark={} run={}", benchmarkId, runId);

        this.benchmarkId = null;
        this.runId = null;
        return true;
    }

    void collectMetrics() {
        if (!acceptingCollection || Thread.currentThread().isInterrupted()) {
            return;
        }

        try {
            var nodes = nodeDiscoveryPort.discoverNodes();
            if (!acceptingCollection || Thread.currentThread().isInterrupted()) {
                return;
            }
            pruneScraperStates(nodes);

            String localId = getLocalMachineId();

            for (var node : nodes) {
                if (!acceptingCollection || Thread.currentThread().isInterrupted()) {
                    return;
                }
                boolean isLocalNode = node.machineId().equals(localId);
                String nodeExporterHost = isLocalNode ? localNodeExporterHost : node.ipAddress();
                String cadvisorHost = isLocalNode ? localCadvisorHost : node.ipAddress();
                String nodeExporterUrl = "http://" + nodeExporterHost + ":" + nodeExporterPortNumber;
                String cadvisorUrl = "http://" + cadvisorHost + ":" + cadvisorPortNumber;
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
                    if (acceptingCollection && !Thread.currentThread().isInterrupted()) {
                        synchronized (metricsDbLock) {
                            resourceMetricRepository.save(hostMetric);
                        }
                    }
                    logScraperSuccess(node.machineId(), ScraperType.NODE_EXPORTER);
                } catch (Exception e) {
                    logScraperFailure(node.machineId(), ScraperType.NODE_EXPORTER, e);
                }

                try {
                    var containerStatsMap = cadvisorPort.scrapeAllContainerStats(cadvisorUrl);

                    Map<String, ContainerInfo> idToInfo;
                    if (isLocalNode) {
                        var containers = containerPort.listContainers();
                        idToInfo = containers.stream()
                                .collect(Collectors.toMap(c -> c.id(), c -> c));
                    } else {
                        idToInfo = Map.of();
                    }

                    for (var entry : containerStatsMap.entrySet()) {
                        if (!acceptingCollection || Thread.currentThread().isInterrupted()) {
                            return;
                        }
                        var containerId = entry.getKey();
                        var stats = entry.getValue();
                        var containerInfo = idToInfo.get(containerId);

                        // Determine service name: use Swarm service name if available, otherwise container name
                        String serviceName;
                        LocalDateTime startedAt = null;
                        if (containerInfo != null) {
                            serviceName = containerInfo.serviceName() != null
                                    ? containerInfo.serviceName()  // Swarm service
                                    : containerInfo.name();         // Standalone container
                            startedAt = containerInfo.startedAt();
                        } else {
                            // Fallback for remote nodes where we don't have container info
                            serviceName = containerId;
                        }

                        var containerMetric = ResourceMetric.builder()
                                .collectedAt(LocalDateTime.now())
                                .targetType(TargetType.SERVICE)
                                .targetName(serviceName)
                                .machineId(node.machineId())
                                .containerId(containerId)
                                .startedAt(startedAt)
                                .cpuPercentage(stats.cpuPercentage())
                                .memoryUsageBytes((double) stats.memoryUsageBytes())
                                .memoryLimitBytes((double) stats.memoryLimitBytes())
                                .networkInBytes((double) stats.networkInBytes())
                                .networkOutBytes((double) stats.networkOutBytes())
                                .blockInBytes((double) stats.blockInBytes())
                                .blockOutBytes((double) stats.blockOutBytes())
                                .build();
                        if (acceptingCollection && !Thread.currentThread().isInterrupted()) {
                            synchronized (metricsDbLock) {
                                resourceMetricRepository.save(containerMetric);
                            }
                        }
                    }
                    logScraperSuccess(node.machineId(), ScraperType.CADVISOR);
                } catch (Exception e) {
                    logScraperFailure(node.machineId(), ScraperType.CADVISOR, e);
                }
            }
        } catch (Exception e) {
            log.warn("Unexpected error in collectMetrics", e);
        }
    }

    void flushMetrics() {
        flushMetrics(true);
    }

    private FlushResult flushMetrics(boolean allowRetryLogging) {
        if (!acceptingCollection && allowRetryLogging) {
            return new FlushResult(FlushStatus.SKIPPED, 0);
        }

        try {
            List<ResourceMetric> unflushed;
            synchronized (metricsDbLock) {
                unflushed = resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc();
            }
            if (unflushed.isEmpty()) {
                return new FlushResult(FlushStatus.SKIPPED, 0);
            }

            if (benchmarkId == null || runId == null) {
                log.warn("flushMetrics called before startCollection (benchmarkId={}, runId={}); skipping flush",
                        benchmarkId, runId);
                return new FlushResult(FlushStatus.SKIPPED, unflushed.size());
            }

            // Filter out legacy metrics with null machineId and delete them
            boolean hasNullMachineId = unflushed.stream().anyMatch(m -> m.getMachineId() == null);
            if (hasNullMachineId) {
                log.warn("Deleting legacy resource metrics with null machineId — these cannot be flushed");
                synchronized (metricsDbLock) {
                    resourceMetricRepository.deleteByMachineIdIsNull();
                }
            }

            unflushed = unflushed.stream()
                    .filter(m -> m.getMachineId() != null)
                    .toList();
            if (unflushed.isEmpty()) {
                return new FlushResult(FlushStatus.SKIPPED, 0);
            }

            // Group host metrics by machineId
            var hostMetrics = unflushed.stream()
                    .filter(m -> m.getTargetType() == TargetType.HOST)
                    .collect(Collectors.groupingBy(ResourceMetric::getMachineId, LinkedHashMap::new, Collectors.toList()));

            // Group service metrics: machineId -> serviceName -> containerId -> metrics[]
            var serviceMetrics = unflushed.stream()
                    .filter(m -> m.getTargetType() == TargetType.SERVICE)
                    .collect(Collectors.groupingBy(
                            ResourceMetric::getMachineId,
                            LinkedHashMap::new,
                            Collectors.groupingBy(
                                    ResourceMetric::getTargetName,
                                    LinkedHashMap::new,
                                    Collectors.groupingBy(
                                            m -> m.getContainerId() != null ? m.getContainerId() : "unknown",
                                            LinkedHashMap::new,
                                            Collectors.toList()))));

            List<MetricResourceNodeDTO> nodeDTOs = new ArrayList<>();

            // Build node DTOs from host metrics, attaching only services with the same machineId
            for (var entry : hostMetrics.entrySet()) {
                String machineId = entry.getKey();
                var servicesForNode = serviceMetrics.getOrDefault(machineId, new LinkedHashMap<>());

                List<MetricResourceServiceDTO> serviceDTOs = servicesForNode.entrySet().stream()
                        .map(serviceEntry -> {
                            String serviceName = serviceEntry.getKey();
                            var replicaMap = serviceEntry.getValue();

                            List<MetricResourceServiceReplicaDTO> replicaDTOs = replicaMap.entrySet().stream()
                                    .map(replicaEntry -> {
                                        String containerId = replicaEntry.getKey();
                                        List<ResourceMetric> replicaMetrics = replicaEntry.getValue();
                                        LocalDateTime startedAt = replicaMetrics.stream()
                                                .map(ResourceMetric::getStartedAt)
                                                .filter(java.util.Objects::nonNull)
                                                .findFirst()
                                                .orElse(null);

                                        return new MetricResourceServiceReplicaDTO()
                                                .containerId(containerId)
                                                .startedAt(startedAt)
                                                .metrics(resourceMetricMapper.toDataPoints(replicaMetrics));
                                    })
                                    .toList();

                            return new MetricResourceServiceDTO()
                                    .serviceName(serviceName)
                                    .replicas(replicaDTOs);
                        })
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
                var servicesForNode = entry.getValue();

                List<MetricResourceServiceDTO> serviceDTOs = servicesForNode.entrySet().stream()
                        .map(serviceEntry -> {
                            String serviceName = serviceEntry.getKey();
                            Map<String, List<ResourceMetric>> replicaMap = serviceEntry.getValue();

                            List<MetricResourceServiceReplicaDTO> replicaDTOs = replicaMap.entrySet().stream()
                                    .map(replicaEntry -> {
                                        String containerId = replicaEntry.getKey();
                                        List<ResourceMetric> replicaMetrics = replicaEntry.getValue();
                                        LocalDateTime startedAt = replicaMetrics.stream()
                                                .map(ResourceMetric::getStartedAt)
                                                .filter(java.util.Objects::nonNull)
                                                .findFirst()
                                                .orElse(null);

                                        return new MetricResourceServiceReplicaDTO()
                                                .containerId(containerId)
                                                .startedAt(startedAt)
                                                .metrics(resourceMetricMapper.toDataPoints(replicaMetrics));
                                    })
                                    .toList();

                            return new MetricResourceServiceDTO()
                                    .serviceName(serviceName)
                                    .replicas(replicaDTOs);
                        })
                        .toList();

                var nodeDTO = new MetricResourceNodeDTO()
                        .machineId(machineId)
                        .metrics(List.of())
                        .services(serviceDTOs);
                nodeDTOs.add(nodeDTO);
            }

            if (nodeDTOs.isEmpty()) {
                log.warn("No metrics to flush despite unflushed records; skipping flush");
                return new FlushResult(FlushStatus.SKIPPED, unflushed.size());
            }

            var dto = new MetricResourceDTO().hosts(nodeDTOs);
            List<UUID> flushedIds = unflushed.stream().map(ResourceMetric::getId).toList();

            try {
                viplevApiPort.sendResourceMetrics(benchmarkId, runId, dto);
                synchronized (metricsDbLock) {
                    resourceMetricRepository.deleteAllByIdInBatch(flushedIds);
                }
                log.debug("Flushed {} metrics to VIPLEV", unflushed.size());
                return new FlushResult(FlushStatus.SENT, unflushed.size());
            } catch (Exception e) {
                if (allowRetryLogging) {
                    log.warn("Failed to send resource metrics to VIPLEV; will retry on next flush", e);
                } else {
                    log.warn("Failed to send resource metrics to VIPLEV during stopCollection", e);
                }
                return new FlushResult(FlushStatus.FAILED, unflushed.size());
            }
        } catch (Exception e) {
            log.warn("Unexpected error in flushMetrics", e);
            return new FlushResult(FlushStatus.FAILED, 0);
        }
    }

    private String getLocalMachineId() {
        if (localMachineId == null) {
            localMachineId = nodeDiscoveryPort.getLocalNodeId();
        }
        return localMachineId;
    }

    ScraperTransition markScraperFailure(String machineId, ScraperType scraperType) {
        ScraperKey key = new ScraperKey(machineId, scraperType);
        ScraperHealth previous = scraperHealthStates.put(key, ScraperHealth.DEGRADED);
        if (previous == ScraperHealth.DEGRADED) {
            return ScraperTransition.NONE;
        }
        return ScraperTransition.DEGRADED;
    }

    ScraperTransition markScraperSuccess(String machineId, ScraperType scraperType) {
        ScraperKey key = new ScraperKey(machineId, scraperType);
        ScraperHealth previous = scraperHealthStates.put(key, ScraperHealth.HEALTHY);
        if (previous == ScraperHealth.DEGRADED) {
            return ScraperTransition.RECOVERED;
        }
        return ScraperTransition.NONE;
    }

    void pruneScraperStates(List<dk.viplev.agent.domain.model.NodeInfo> nodes) {
        Set<String> activeMachineIds = new HashSet<>();
        for (var node : nodes) {
            activeMachineIds.add(node.machineId());
        }
        scraperHealthStates.keySet().removeIf(key -> !activeMachineIds.contains(key.machineId()));
    }

    @Nullable
    ScraperHealth getScraperHealth(String machineId, ScraperType scraperType) {
        return scraperHealthStates.get(new ScraperKey(machineId, scraperType));
    }

    private void logScraperFailure(String machineId, ScraperType scraperType, Exception e) {
        ScraperTransition transition = markScraperFailure(machineId, scraperType);
        String errorMessage = e.getMessage();
        if (transition == ScraperTransition.DEGRADED) {
            log.warn("Metric scraper {} degraded for node={}: {}", scraperType.logName, machineId, errorMessage);
            log.debug("Metric scraper {} degraded stacktrace for node={}", scraperType.logName, machineId, e);
            return;
        }
        log.debug("Metric scraper {} still degraded for node={}: {}",
                scraperType.logName,
                machineId,
                errorMessage);
    }

    private void logScraperSuccess(String machineId, ScraperType scraperType) {
        ScraperTransition transition = markScraperSuccess(machineId, scraperType);
        if (transition == ScraperTransition.RECOVERED) {
            log.info("Metric scraper {} recovered for node={}", scraperType.logName, machineId);
        }
    }

    private void clearBufferedMetrics(String reason) {
        synchronized (metricsDbLock) {
            long rowCount = resourceMetricRepository.count();
            if (rowCount == 0) {
                return;
            }
            resourceMetricRepository.deleteAllInBatch();
            log.info("Cleared {} buffered resource metrics while {}", rowCount, reason);
        }
    }

    private void cancelScheduledTask(@Nullable ScheduledFuture<?> task) {
        if (task == null) {
            return;
        }
        task.cancel(true);
    }
}
