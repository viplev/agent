package dk.viplev.agent.adapter.outbound.metrics.nodeexporter;

import dk.viplev.agent.domain.model.HostStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NodeExporterAdapterTest {

    private static final String BASE_URL = "http://viplev-node-exporter:9100";

    @Mock
    private RestTemplate restTemplate;

    private NodeExporterAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new NodeExporterAdapter(restTemplate);
    }

    @Test
    void scrapeHostStats_returnsCorrectMetrics() {
        when(restTemplate.getForObject(eq(BASE_URL + "/metrics"), eq(String.class)))
                .thenReturn(buildMetricsFixture(
                        /* totalCpu */ 10000.0, /* idleCpu */ 8000.0,
                        /* memTotal */ 16_000_000_000.0, /* memAvailable */ 8_000_000_000.0,
                        /* netIn */ 100_000L, /* netOut */ 200_000L,
                        /* diskIn */ 500_000_000L, /* diskOut */ 300_000_000L));

        // First call — CPU returns 0 (no prior reading)
        adapter.scrapeHostStats(BASE_URL);

        when(restTemplate.getForObject(eq(BASE_URL + "/metrics"), eq(String.class)))
                .thenReturn(buildMetricsFixture(
                        /* totalCpu */ 11000.0, /* idleCpu */ 8800.0,
                        /* memTotal */ 16_000_000_000.0, /* memAvailable */ 6_000_000_000.0,
                        /* netIn */ 150_000L, /* netOut */ 250_000L,
                        /* diskIn */ 600_000_000L, /* diskOut */ 400_000_000L));

        HostStats stats = adapter.scrapeHostStats(BASE_URL);

        // CPU: totalDelta=1000, idleDelta=800 → (1000-800)/1000 * 100 = 20%
        assertThat(stats.cpuPercentage()).isCloseTo(20.0, within(0.01));
        // Memory: 16GB - 6GB = 10GB used
        assertThat(stats.memoryUsageBytes()).isEqualTo(10_000_000_000L);
        assertThat(stats.memoryLimitBytes()).isEqualTo(16_000_000_000L);
        // Network: second reading (cumulative counters)
        assertThat(stats.networkInBytes()).isEqualTo(150_000L);
        assertThat(stats.networkOutBytes()).isEqualTo(250_000L);
        // Disk: second reading
        assertThat(stats.blockInBytes()).isEqualTo(600_000_000L);
        assertThat(stats.blockOutBytes()).isEqualTo(400_000_000L);
    }

    @Test
    void scrapeHostStats_cpuPercentageRequiresTwoScrapes() {
        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(buildMetricsFixture(10000.0, 8000.0,
                        16_000_000_000.0, 8_000_000_000.0, 0, 0, 0, 0));

        HostStats firstResult = adapter.scrapeHostStats(BASE_URL);

        assertThat(firstResult.cpuPercentage()).isZero();

        when(restTemplate.getForObject(anyString(), eq(String.class)))
                .thenReturn(buildMetricsFixture(10500.0, 8250.0,
                        16_000_000_000.0, 8_000_000_000.0, 0, 0, 0, 0));

        HostStats secondResult = adapter.scrapeHostStats(BASE_URL);

        // totalDelta=500, idleDelta=250 → (500-250)/500 * 100 = 50%
        assertThat(secondResult.cpuPercentage()).isCloseTo(50.0, within(0.01));
    }

    @Test
    void scrapeHostStats_excludesLoopbackNetwork() {
        String metrics = """
                node_memory_MemTotal_bytes 16000000000
                node_memory_MemAvailable_bytes 8000000000
                node_cpu_seconds_total{cpu="0",mode="idle"} 1000.0
                node_cpu_seconds_total{cpu="0",mode="system"} 200.0
                node_network_receive_bytes_total{device="eth0"} 100000
                node_network_receive_bytes_total{device="lo"} 9999
                node_network_transmit_bytes_total{device="eth0"} 200000
                node_network_transmit_bytes_total{device="lo"} 9999
                """;
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(metrics);

        HostStats stats = adapter.scrapeHostStats(BASE_URL);

        assertThat(stats.networkInBytes()).isEqualTo(100_000L);
        assertThat(stats.networkOutBytes()).isEqualTo(200_000L);
    }

    @Test
    void scrapeHostStats_excludesDeviceMapperDisks() {
        String metrics = """
                node_memory_MemTotal_bytes 16000000000
                node_memory_MemAvailable_bytes 8000000000
                node_cpu_seconds_total{cpu="0",mode="idle"} 1000.0
                node_cpu_seconds_total{cpu="0",mode="system"} 200.0
                node_disk_read_bytes_total{device="sda"} 500000000
                node_disk_read_bytes_total{device="dm-0"} 500000000
                node_disk_read_bytes_total{device="dm-1"} 500000000
                node_disk_written_bytes_total{device="sda"} 300000000
                node_disk_written_bytes_total{device="dm-0"} 300000000
                """;
        when(restTemplate.getForObject(anyString(), eq(String.class))).thenReturn(metrics);

        HostStats stats = adapter.scrapeHostStats(BASE_URL);

        assertThat(stats.blockInBytes()).isEqualTo(500_000_000L);
        assertThat(stats.blockOutBytes()).isEqualTo(300_000_000L);
    }

    @Test
    void scrapeHostStats_cpuDeltaIsPerBaseUrl() {
        String baseUrl1 = "http://node1:9100";
        String baseUrl2 = "http://node2:9100";

        String firstReading = buildMetricsFixture(10000.0, 8000.0,
                16_000_000_000.0, 8_000_000_000.0, 0, 0, 0, 0);
        String secondReading = buildMetricsFixture(10400.0, 8200.0,
                16_000_000_000.0, 8_000_000_000.0, 0, 0, 0, 0);

        when(restTemplate.getForObject(eq(baseUrl1 + "/metrics"), eq(String.class))).thenReturn(firstReading);
        when(restTemplate.getForObject(eq(baseUrl2 + "/metrics"), eq(String.class))).thenReturn(firstReading);

        adapter.scrapeHostStats(baseUrl1); // seed node1
        adapter.scrapeHostStats(baseUrl2); // seed node2 separately — should not affect node1

        when(restTemplate.getForObject(eq(baseUrl1 + "/metrics"), eq(String.class))).thenReturn(secondReading);

        // totalDelta=400, idleDelta=200 → (400-200)/400 * 100 = 50%
        HostStats node1Stats = adapter.scrapeHostStats(baseUrl1);
        assertThat(node1Stats.cpuPercentage()).isCloseTo(50.0, within(0.01));

        // node2 still only has seed — next call returns 0 (no delta yet stored as second reading)
        when(restTemplate.getForObject(eq(baseUrl2 + "/metrics"), eq(String.class))).thenReturn(secondReading);
        HostStats node2Stats = adapter.scrapeHostStats(baseUrl2);
        assertThat(node2Stats.cpuPercentage()).isCloseTo(50.0, within(0.01));
    }

    // -- Helpers --

    private String buildMetricsFixture(
            double totalCpuSeconds, double idleCpuSeconds,
            double memTotal, double memAvailable,
            long netIn, long netOut,
            long diskIn, long diskOut) {

        // Split total into idle + non-idle (system) so that summing works correctly
        double systemCpuSeconds = totalCpuSeconds - idleCpuSeconds;

        return String.format("""
                node_memory_MemTotal_bytes %s
                node_memory_MemAvailable_bytes %s
                node_cpu_seconds_total{cpu="0",mode="idle"} %s
                node_cpu_seconds_total{cpu="0",mode="iowait"} 0.0
                node_cpu_seconds_total{cpu="0",mode="system"} %s
                node_network_receive_bytes_total{device="eth0"} %d
                node_network_transmit_bytes_total{device="eth0"} %d
                node_disk_read_bytes_total{device="sda"} %d
                node_disk_written_bytes_total{device="sda"} %d
                """,
                memTotal, memAvailable,
                idleCpuSeconds, systemCpuSeconds,
                netIn, netOut,
                diskIn, diskOut);
    }
}
