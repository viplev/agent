package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.mapper.ResourceMetricMapper;
import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.ContainerStats;
import dk.viplev.agent.domain.model.HostStats;
import dk.viplev.agent.domain.model.NodeInfo;
import dk.viplev.agent.domain.model.ResourceMetric;
import dk.viplev.agent.domain.model.TargetType;
import dk.viplev.agent.generated.model.MetricResourceDTO;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import dk.viplev.agent.port.outbound.db.ResourceMetricRepository;
import dk.viplev.agent.port.outbound.discovery.NodeDiscoveryPort;
import dk.viplev.agent.port.outbound.metrics.CadvisorPort;
import dk.viplev.agent.port.outbound.metrics.NodeExporterPort;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.AfterEach;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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

    private static final UUID BENCHMARK_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID RUN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final NodeInfo TEST_NODE = new NodeInfo(
            "machine-abc", "test-host", "192.168.1.10", "Linux", "5.15.0", 4, 8_000_000_000L);

    private static final HostStats SAMPLE_HOST_STATS = new HostStats(
            25.0, 4_000_000_000L, 8_000_000_000L, 100_000L, 50_000L, 1_000_000L, 500_000L);

    private static final ContainerStats SAMPLE_CONTAINER_STATS = new ContainerStats(
            10.0, 512_000_000L, 1_000_000_000L, 20_000L, 10_000L, 200_000L, 100_000L);

    @BeforeEach
    void setUp() {
        service = new MetricCollectionServiceImpl(
                nodeExporterPort, cadvisorPort, containerPort,
                nodeDiscoveryPort, resourceMetricRepository, viplevApiPort, resourceMetricMapper);
    }

    @AfterEach
    void tearDown() {
        service.stopCollection();
    }

    @Test
    void startCollection_schedulesCollectionAndFlushTasks() {
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(TEST_NODE));
        when(nodeExporterPort.scrapeHostStats(anyString())).thenReturn(SAMPLE_HOST_STATS);
        when(cadvisorPort.scrapeAllContainerStats(anyString())).thenReturn(Map.of());
        when(containerPort.listContainers()).thenReturn(List.of());
        when(resourceMetricRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.startCollection(BENCHMARK_ID, RUN_ID);

        verify(nodeExporterPort, timeout(3000).atLeastOnce()).scrapeHostStats(anyString());
        verify(cadvisorPort, timeout(3000).atLeastOnce()).scrapeAllContainerStats(anyString());
    }

    @Test
    void stopCollection_performsFinalFlush() {
        var unflushedMetrics = List.of(
                hostMetric("machine-abc"),
                serviceMetric("nginx")
        );
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(TEST_NODE));
        when(nodeExporterPort.scrapeHostStats(anyString())).thenReturn(SAMPLE_HOST_STATS);
        when(cadvisorPort.scrapeAllContainerStats(anyString())).thenReturn(Map.of());
        when(containerPort.listContainers()).thenReturn(List.of());
        when(resourceMetricRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc()).thenReturn(unflushedMetrics);

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.stopCollection();

        verify(viplevApiPort).sendResourceMetrics(any(), any(), any());
    }

    @Test
    void flushMetrics_groupsByHostAndService() {
        var hostMetric = hostMetric("machine-abc");
        var serviceMetric1 = serviceMetric("nginx");
        var serviceMetric2 = serviceMetric("redis");

        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(List.of(hostMetric, serviceMetric1, serviceMetric2));
        when(resourceMetricRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

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
    }

    @Test
    void flushMetrics_onFailure_leavesMetricsUnflushed() {
        var metric = hostMetric("machine-abc");
        when(resourceMetricRepository.findByFlushedFalseOrderByCollectedAtAsc())
                .thenReturn(List.of(metric));
        doThrow(new RuntimeException("API unavailable"))
                .when(viplevApiPort).sendResourceMetrics(any(), any(), any());

        service.flushMetrics();

        assertThat(metric.isFlushed()).isFalse();
        verify(resourceMetricRepository, never()).saveAll(any());
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
    }

    @Test
    void collectMetrics_exceptionDoesNotStopScheduler() {
        when(nodeDiscoveryPort.discoverNodes())
                .thenThrow(new RuntimeException("Node discovery failed"))
                .thenReturn(List.of(TEST_NODE));
        when(nodeExporterPort.scrapeHostStats(anyString())).thenReturn(SAMPLE_HOST_STATS);
        when(cadvisorPort.scrapeAllContainerStats(anyString())).thenReturn(Map.of());
        when(containerPort.listContainers()).thenReturn(List.of());
        when(resourceMetricRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.startCollection(BENCHMARK_ID, RUN_ID);

        // The second call should succeed; scrapeHostStats is called after the first failure is swallowed
        verify(nodeExporterPort, timeout(3000).atLeastOnce()).scrapeHostStats(anyString());
    }

    @Test
    void startCollection_doubleStart_logsWarning() {
        when(nodeDiscoveryPort.discoverNodes()).thenReturn(List.of(TEST_NODE));
        when(nodeExporterPort.scrapeHostStats(anyString())).thenReturn(SAMPLE_HOST_STATS);
        when(cadvisorPort.scrapeAllContainerStats(anyString())).thenReturn(Map.of());
        when(containerPort.listContainers()).thenReturn(List.of());
        when(resourceMetricRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.startCollection(BENCHMARK_ID, RUN_ID);
        service.startCollection(BENCHMARK_ID, RUN_ID);  // should be a no-op, no exception
    }

    // --- Helpers ---

    private ResourceMetric hostMetric(String machineId) {
        return ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.HOST)
                .targetName(machineId)
                .cpuPercentage(25.0)
                .memoryUsageBytes(4_000_000_000.0)
                .memoryLimitBytes(8_000_000_000.0)
                .networkInBytes(100_000.0)
                .networkOutBytes(50_000.0)
                .blockInBytes(1_000_000.0)
                .blockOutBytes(500_000.0)
                .build();
    }

    private ResourceMetric serviceMetric(String serviceName) {
        return ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.SERVICE)
                .targetName(serviceName)
                .cpuPercentage(10.0)
                .memoryUsageBytes(512_000_000.0)
                .memoryLimitBytes(1_000_000_000.0)
                .networkInBytes(20_000.0)
                .networkOutBytes(10_000.0)
                .blockInBytes(200_000.0)
                .blockOutBytes(100_000.0)
                .build();
    }
}
