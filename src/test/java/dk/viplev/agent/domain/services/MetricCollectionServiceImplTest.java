package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.mapper.ResourceMetricMapper;
import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.ContainerStats;
import dk.viplev.agent.domain.model.HostStats;
import dk.viplev.agent.domain.model.NodeInfo;
import dk.viplev.agent.domain.model.ResourceMetric;
import dk.viplev.agent.domain.model.TargetType;
import dk.viplev.agent.generated.model.MetricResourceDTO;
import dk.viplev.agent.generated.model.MetricResourceServiceDTO;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.db.ResourceMetricRepository;
import dk.viplev.agent.port.outbound.discovery.NodeDiscoveryPort;
import dk.viplev.agent.port.outbound.metrics.CadvisorPort;
import dk.viplev.agent.port.outbound.metrics.NodeExporterPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetricCollectionServiceImplTest {

    @Mock
    private NodeExporterPort nodeExporterPort;

    @Mock
    private CadvisorPort cadvisorPort;

    @Mock
    private ContainerPort containerPort;

    @Mock
    private NodeDiscoveryPort nodeDiscoveryPort;

    @Mock
    private ResourceMetricRepository resourceMetricRepository;

    @Mock
    private ViplevApiPort viplevApiPort;

    private final ResourceMetricMapper resourceMetricMapper = Mappers.getMapper(ResourceMetricMapper.class);

    private MetricCollectionServiceImpl service;

    // Captures runnables submitted to scheduleAtFixedRate so tests can invoke them directly
    private final List<Runnable> scheduledRunnables = new ArrayList<>();
    private ScheduledExecutorService mockExecutor;

    private static final UUID BENCHMARK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final NodeInfo TEST_NODE = new NodeInfo(
            "machine-abc", "test-host", "192.168.1.10", "Linux", "5.15.0", 4, 8_000_000_000L);

    private static final HostStats SAMPLE_HOST_STATS = new HostStats(
            25.0, 4_000_000_000L, 8_000_000_000L, 100_000L, 50_000L, 1_000_000L, 500_000L);

    private static final ContainerStats SAMPLE_CONTAINER_STATS = new ContainerStats(
            10.0, 512_000_000L, 1_000_000_000L, 20_000L, 10_000L, 200_000L, 100_000L);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        scheduledRunnables.clear();
        mockExecutor = mock(ScheduledExecutorService.class);
        lenient().when(mockExecutor.isShutdown()).thenReturn(false);
        lenient().doAnswer(inv -> {
            scheduledRunnables.add(inv.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(mockExecutor).scheduleAtFixedRate(any(Runnable.class), anyLong(), anyLong(), any(TimeUnit.class));

        service = new MetricCollectionServiceImpl(
                nodeExporterPort, cadvisorPort, containerPort,
                nodeDiscoveryPort, resourceMetricRepository, viplevApiPort, resourceMetricMapper,
                9100, 8080, mockExecutor);
    }

    @Test
    void startCollection_schedulesCollectionAndFlushTasks() {
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(TEST_NODE));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("machine-abc");
        when(nodeExporterPort.scrapeHostStats(anyString())).thenReturn(SAMPLE_HOST_STATS);
        when(cadvisorPort.scrapeAllContainerStats(anyString())).thenReturn(Map.of());
        when(containerPort.listContainers()).thenReturn(List.of());
        when(resourceMetricRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.startCollection(BENCHMARK_ID, RUN_ID);

        // Two runnables must be registered: collectMetrics and flushMetrics
        assertThat(scheduledRunnables).hasSize(2);

        // Invoke collectMetrics directly (deterministic, no timeout needed)
        scheduledRunnables.get(0).run();

        verify(nodeExporterPort).scrapeHostStats("http://viplev-node-exporter:9100");
        verify(cadvisorPort).scrapeAllContainerStats("http://viplev-cadvisor:8080");
    }

    @Test
    void stopCollection_performsFinalFlush() {
        var unflushedMetrics = List.of(
                hostMetric("machine-abc"),
                serviceMetric("nginx", "machine-abc")
        );
        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc()).thenReturn(unflushedMetrics);

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.stopCollection();

        verify(viplevApiPort).sendResourceMetrics(any(), any(), any());
    }

    @Test
    void flushMetrics_groupsByHostAndService() {
        var hostMetric = hostMetric("machine-abc");
        var serviceMetric1 = serviceMetric("nginx", "machine-abc");
        var serviceMetric2 = serviceMetric("redis", "machine-abc");

        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(List.of(hostMetric, serviceMetric1, serviceMetric2));

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.flushMetrics();

        var captor = ArgumentCaptor.forClass(MetricResourceDTO.class);
        verify(viplevApiPort).sendResourceMetrics(any(), any(), captor.capture());

        var dto = captor.getValue();
        assertThat(dto.getHosts()).hasSize(1);

        var nodeDTO = dto.getHosts().getFirst();
        assertThat(nodeDTO.getMachineId()).isEqualTo("machine-abc");
        assertThat(nodeDTO.getMetrics()).hasSize(1);
        assertThat(nodeDTO.getServices()).hasSize(2);
        assertThat(nodeDTO.getServices().stream().map(s -> s.getServiceName()))
                .containsExactlyInAnyOrder("nginx", "redis");
        assertThat(nodeDTO.getServices().stream().allMatch(s -> s.getMetrics().size() == 1)).isTrue();
    }

    @Test
    void flushMetrics_multiNode_servicesGroupedPerHost() {
        var hostMetricAbc = hostMetric("machine-abc");
        var hostMetricXyz = hostMetric("machine-xyz");
        var serviceAbcNginx = serviceMetric("nginx", "machine-abc");
        var serviceXyzPostgres = serviceMetric("postgres", "machine-xyz");

        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(List.of(hostMetricAbc, hostMetricXyz, serviceAbcNginx, serviceXyzPostgres));

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.flushMetrics();

        var captor = ArgumentCaptor.forClass(MetricResourceDTO.class);
        verify(viplevApiPort).sendResourceMetrics(any(), any(), captor.capture());

        var dto = captor.getValue();
        assertThat(dto.getHosts()).hasSize(2);

        var nodeDTOAbc = dto.getHosts().stream()
                .filter(n -> "machine-abc".equals(n.getMachineId()))
                .findFirst().orElseThrow();
        assertThat(nodeDTOAbc.getServices()).hasSize(1);
        assertThat(nodeDTOAbc.getServices().getFirst().getServiceName()).isEqualTo("nginx");

        var nodeDTOXyz = dto.getHosts().stream()
                .filter(n -> "machine-xyz".equals(n.getMachineId()))
                .findFirst().orElseThrow();
        assertThat(nodeDTOXyz.getServices()).hasSize(1);
        assertThat(nodeDTOXyz.getServices().getFirst().getServiceName()).isEqualTo("postgres");
    }

    @Test
    void flushMetrics_manyServices_eachServiceKeepsOwnDatapoints() {
        var allMetrics = new ArrayList<ResourceMetric>();
        allMetrics.add(hostMetric("machine-abc"));
        for (int i = 1; i <= 7; i++) {
            allMetrics.add(serviceMetric("service-" + i, "machine-abc"));
        }

        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc()).thenReturn(allMetrics);

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.flushMetrics();

        var captor = ArgumentCaptor.forClass(MetricResourceDTO.class);
        verify(viplevApiPort).sendResourceMetrics(any(), any(), captor.capture());

        var dto = captor.getValue();
        assertThat(dto.getHosts()).hasSize(1);

        var nodeDTO = dto.getHosts().getFirst();
        assertThat(nodeDTO.getServices()).hasSize(7);
        assertThat(nodeDTO.getServices().stream().allMatch(s -> s.getMetrics().size() == 1)).isTrue();
        assertThat(nodeDTO.getServices().stream().map(MetricResourceServiceDTO::getServiceName))
                .containsExactlyInAnyOrder(
                        "service-1", "service-2", "service-3", "service-4", "service-5", "service-6", "service-7");
    }

    @Test
    void flushMetrics_serviceMetricsWithoutHostMetrics_stillFlushes() {
        var serviceMetric = serviceMetric("nginx", "machine-abc");

        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(List.of(serviceMetric));

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.flushMetrics();

        var captor = ArgumentCaptor.forClass(MetricResourceDTO.class);
        verify(viplevApiPort).sendResourceMetrics(any(), any(), captor.capture());

        var dto = captor.getValue();
        assertThat(dto.getHosts()).hasSize(1);

        var nodeDTO = dto.getHosts().getFirst();
        assertThat(nodeDTO.getMachineId()).isEqualTo("machine-abc");
        assertThat(nodeDTO.getMetrics()).isEmpty();
        assertThat(nodeDTO.getServices()).hasSize(1);
        assertThat(nodeDTO.getServices().getFirst().getServiceName()).isEqualTo("nginx");
    }

    @Test
    void flushMetrics_onFailure_leavesMetricsUnflushed() {
        var metric = hostMetric("machine-abc");
        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(List.of(metric));
        doThrow(new RuntimeException("API unavailable"))
                .when(viplevApiPort).sendResourceMetrics(any(), any(), any());

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.flushMetrics();

        assertThat(metric.isFlushed()).isFalse();
        verify(resourceMetricRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    void flushMetrics_noUnflushedMetrics_doesNotCallApi() {
        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(List.of());

        service.flushMetrics();

        verify(viplevApiPort, never()).sendResourceMetrics(any(), any(), any());
    }

    @Test
    void collectMetrics_persistsHostAndContainerMetrics() {
        var container = new ContainerInfo("abc123", "nginx", "nginx:latest", "sha256:aaa",
                "running", null, null, null, null);
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(TEST_NODE));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("machine-abc");
        when(nodeExporterPort.scrapeHostStats(anyString())).thenReturn(SAMPLE_HOST_STATS);
        when(cadvisorPort.scrapeAllContainerStats(anyString()))
                .thenReturn(Map.of("abc123", SAMPLE_CONTAINER_STATS));
        when(containerPort.listContainers()).thenReturn(List.of(container));

        var savedMetrics = new ArrayList<ResourceMetric>();
        when(resourceMetricRepository.save(any())).thenAnswer(inv -> {
            savedMetrics.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        service.collectMetrics();

        assertThat(savedMetrics).hasSize(2);
        assertThat(savedMetrics.stream().filter(m -> m.getTargetType() == TargetType.HOST)).hasSize(1);
        assertThat(savedMetrics.stream().filter(m -> m.getTargetType() == TargetType.SERVICE)).hasSize(1);
        assertThat(savedMetrics.stream()
                .filter(m -> m.getTargetType() == TargetType.HOST)
                .findFirst().get().getTargetName()).isEqualTo("machine-abc");
        assertThat(savedMetrics.stream()
                .filter(m -> m.getTargetType() == TargetType.SERVICE)
                .findFirst().get().getTargetName()).isEqualTo("nginx");

        // Verify machineId is set on both metric types
        assertThat(savedMetrics.stream()
                .filter(m -> m.getTargetType() == TargetType.HOST)
                .findFirst().get().getMachineId()).isEqualTo("machine-abc");
        assertThat(savedMetrics.stream()
                .filter(m -> m.getTargetType() == TargetType.SERVICE)
                .findFirst().get().getMachineId()).isEqualTo("machine-abc");
    }

    @Test
    void collectMetrics_exceptionDoesNotStopScheduler() {
        when(nodeDiscoveryPort.discoverNodes())
                .thenThrow(new RuntimeException("Node discovery failed"));

        // Should not throw
        service.collectMetrics();
    }

    @Test
    void startCollection_doubleStart_logsWarning() {
        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.startCollection(BENCHMARK_ID, RUN_ID);  // should be a no-op, no exception

        // scheduleAtFixedRate should only be called twice (from the first startCollection)
        verify(mockExecutor, org.mockito.Mockito.times(2))
                .scheduleAtFixedRate(any(), anyLong(), anyLong(), any());
    }

    @Test
    void collectMetrics_remoteNode_usesContainerIdAsName() {
        var remoteNode = new NodeInfo("machine-xyz", "remote-host", "10.0.0.2", "Linux", "5.15.0", 4, 8_000_000_000L);
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(remoteNode));
        when(nodeDiscoveryPort.getLocalNodeId()).thenReturn("machine-abc");
        when(nodeExporterPort.scrapeHostStats(anyString())).thenReturn(SAMPLE_HOST_STATS);
        when(cadvisorPort.scrapeAllContainerStats(anyString()))
                .thenReturn(Map.of("abc123", SAMPLE_CONTAINER_STATS));

        var savedMetrics = new ArrayList<ResourceMetric>();
        when(resourceMetricRepository.save(any())).thenAnswer(inv -> {
            savedMetrics.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        service.collectMetrics();

        // containerPort.listContainers() should NOT be called for a remote node
        verify(containerPort, never()).listContainers();

        // The service metric should use the container ID as the name
        var serviceMetric = savedMetrics.stream()
                .filter(m -> m.getTargetType() == TargetType.SERVICE)
                .findFirst().orElseThrow();
        assertThat(serviceMetric.getTargetName()).isEqualTo("abc123");
    }

    @Test
    void flushMetrics_nullBenchmarkId_skipsFlush() {
        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(List.of(hostMetric("machine-abc")));

        // Do NOT call startCollection — benchmarkId and runId remain null
        service.flushMetrics();

        verify(viplevApiPort, never()).sendResourceMetrics(any(), any(), any());
    }

    @Test
    void flushMetrics_metricsWithNullMachineId_expiresLegacyRows() {
        var nullMachineIdMetric = nullMachineIdMetric();
        var validMetric = hostMetric("machine-abc");

        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(new ArrayList<>(List.of(nullMachineIdMetric, validMetric)));

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.flushMetrics();

        // The null-machineId metric should have been deleted via deleteByMachineIdIsNull
        verify(resourceMetricRepository).deleteByMachineIdIsNull();

        // API should be called only with the valid metric
        var captor = ArgumentCaptor.forClass(MetricResourceDTO.class);
        verify(viplevApiPort).sendResourceMetrics(any(), any(), captor.capture());
        var dto = captor.getValue();
        assertThat(dto.getHosts()).hasSize(1);
        assertThat(dto.getHosts().getFirst().getMachineId()).isEqualTo("machine-abc");
    }

    @Test
    void scraperState_firstFailure_transitionsToDegraded() {
        var transition = service.markScraperFailure("machine-abc", MetricCollectionServiceImpl.ScraperType.NODE_EXPORTER);

        assertThat(transition).isEqualTo(MetricCollectionServiceImpl.ScraperTransition.DEGRADED);
        assertThat(service.getScraperHealth("machine-abc", MetricCollectionServiceImpl.ScraperType.NODE_EXPORTER))
                .isEqualTo(MetricCollectionServiceImpl.ScraperHealth.DEGRADED);
    }

    @Test
    void scraperState_repeatedFailure_doesNotRetriggerTransition() {
        service.markScraperFailure("machine-abc", MetricCollectionServiceImpl.ScraperType.CADVISOR);

        var transition = service.markScraperFailure("machine-abc", MetricCollectionServiceImpl.ScraperType.CADVISOR);

        assertThat(transition).isEqualTo(MetricCollectionServiceImpl.ScraperTransition.NONE);
        assertThat(service.getScraperHealth("machine-abc", MetricCollectionServiceImpl.ScraperType.CADVISOR))
                .isEqualTo(MetricCollectionServiceImpl.ScraperHealth.DEGRADED);
    }

    @Test
    void scraperState_recovery_logsAsSingleTransition() {
        service.markScraperFailure("machine-abc", MetricCollectionServiceImpl.ScraperType.NODE_EXPORTER);

        var firstRecovery = service.markScraperSuccess("machine-abc", MetricCollectionServiceImpl.ScraperType.NODE_EXPORTER);
        var secondRecovery = service.markScraperSuccess("machine-abc", MetricCollectionServiceImpl.ScraperType.NODE_EXPORTER);

        assertThat(firstRecovery).isEqualTo(MetricCollectionServiceImpl.ScraperTransition.RECOVERED);
        assertThat(secondRecovery).isEqualTo(MetricCollectionServiceImpl.ScraperTransition.NONE);
        assertThat(service.getScraperHealth("machine-abc", MetricCollectionServiceImpl.ScraperType.NODE_EXPORTER))
                .isEqualTo(MetricCollectionServiceImpl.ScraperHealth.HEALTHY);
    }

    @Test
    void scraperState_failureAfterRecovery_startsNewFailurePeriod() {
        service.markScraperFailure("machine-abc", MetricCollectionServiceImpl.ScraperType.CADVISOR);
        service.markScraperSuccess("machine-abc", MetricCollectionServiceImpl.ScraperType.CADVISOR);

        var transition = service.markScraperFailure("machine-abc", MetricCollectionServiceImpl.ScraperType.CADVISOR);

        assertThat(transition).isEqualTo(MetricCollectionServiceImpl.ScraperTransition.DEGRADED);
        assertThat(service.getScraperHealth("machine-abc", MetricCollectionServiceImpl.ScraperType.CADVISOR))
                .isEqualTo(MetricCollectionServiceImpl.ScraperHealth.DEGRADED);
    }

    @Test
    void pruneScraperStates_removesMissingNodes() {
        service.markScraperFailure("machine-abc", MetricCollectionServiceImpl.ScraperType.NODE_EXPORTER);
        service.markScraperFailure("machine-xyz", MetricCollectionServiceImpl.ScraperType.CADVISOR);

        service.pruneScraperStates(List.of(TEST_NODE));

        assertThat(service.getScraperHealth("machine-abc", MetricCollectionServiceImpl.ScraperType.NODE_EXPORTER))
                .isEqualTo(MetricCollectionServiceImpl.ScraperHealth.DEGRADED);
        assertThat(service.getScraperHealth("machine-xyz", MetricCollectionServiceImpl.ScraperType.CADVISOR))
                .isNull();
    }

    // --- Helpers ---

    private ResourceMetric hostMetric(String machineId) {
        return ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.HOST)
                .targetName(machineId)
                .machineId(machineId)
                .cpuPercentage(25.0)
                .memoryUsageBytes(4_000_000_000.0)
                .memoryLimitBytes(8_000_000_000.0)
                .networkInBytes(100_000.0)
                .networkOutBytes(50_000.0)
                .blockInBytes(1_000_000.0)
                .blockOutBytes(500_000.0)
                .build();
    }

    private ResourceMetric serviceMetric(String serviceName, String machineId) {
        return ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.SERVICE)
                .targetName(serviceName)
                .machineId(machineId)
                .cpuPercentage(10.0)
                .memoryUsageBytes(512_000_000.0)
                .memoryLimitBytes(1_000_000_000.0)
                .networkInBytes(20_000.0)
                .networkOutBytes(10_000.0)
                .blockInBytes(200_000.0)
                .blockOutBytes(100_000.0)
                .build();
    }

    private ResourceMetric nullMachineIdMetric() {
        return ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.HOST)
                .targetName("legacy-host")
                .machineId(null)
                .cpuPercentage(5.0)
                .memoryUsageBytes(1_000_000_000.0)
                .memoryLimitBytes(2_000_000_000.0)
                .networkInBytes(10_000.0)
                .networkOutBytes(5_000.0)
                .blockInBytes(100_000.0)
                .blockOutBytes(50_000.0)
                .build();
    }
}
