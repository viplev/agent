package dk.viplev.agent.adapter.outbound.metrics.nodeexporter;

import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import dk.viplev.agent.domain.model.HostStats;
import dk.viplev.agent.port.outbound.metrics.NodeExporterPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@Profile("docker")
public class NodeExporterAdapter implements NodeExporterPort {

    private static final String METRICS_PATH = "/metrics";

    private final RestTemplate restTemplate;
    private final PrometheusMetricsParser parser;
    private final ConcurrentHashMap<String, CpuReading> previousCpuReadings = new ConcurrentHashMap<>();

    public NodeExporterAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
        this.parser = new PrometheusMetricsParser();
    }

    @Override
    public HostStats scrapeHostStats(String baseUrl) {
        String metricsText = fetchMetrics(baseUrl);

        double cpuPercentage = calculateCpuPercentage(metricsText, baseUrl);

        double memTotal = parser.parseGaugeValue(metricsText, "node_memory_MemTotal_bytes");
        double memAvailable = parser.parseGaugeValue(metricsText, "node_memory_MemAvailable_bytes");
        long memoryUsage = (long) (memTotal - memAvailable);
        long memoryLimit = (long) memTotal;

        long networkIn = sumDeviceMetric(metricsText, "node_network_receive_bytes_total", "lo");
        long networkOut = sumDeviceMetric(metricsText, "node_network_transmit_bytes_total", "lo");

        long blockIn = sumDiskMetric(metricsText, "node_disk_read_bytes_total");
        long blockOut = sumDiskMetric(metricsText, "node_disk_written_bytes_total");

        return new HostStats(cpuPercentage, memoryUsage, memoryLimit, networkIn, networkOut, blockIn, blockOut);
    }

    private String fetchMetrics(String baseUrl) {
        try {
            String url = baseUrl + METRICS_PATH;
            String response = restTemplate.getForObject(url, String.class);
            if (response == null) {
                throw new ContainerRuntimeException("Empty response from node_exporter at " + url);
            }
            return response;
        } catch (RestClientException e) {
            throw new ContainerRuntimeException("Failed to scrape node_exporter at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    private double calculateCpuPercentage(String metricsText, String baseUrl) {
        Map<Map<String, String>, Double> cpuData = parser.parseLabeledValues(metricsText, "node_cpu_seconds_total");

        double totalSeconds = cpuData.values().stream().mapToDouble(Double::doubleValue).sum();
        double idleSeconds = cpuData.entrySet().stream()
                .filter(e -> {
                    String mode = e.getKey().get("mode");
                    return "idle".equals(mode) || "iowait".equals(mode);
                })
                .mapToDouble(Map.Entry::getValue)
                .sum();

        CpuReading current = new CpuReading(totalSeconds, idleSeconds);
        CpuReading previous = previousCpuReadings.put(baseUrl, current);

        if (previous == null) {
            return 0.0;
        }

        double totalDelta = current.totalSeconds() - previous.totalSeconds();
        double idleDelta = current.idleSeconds() - previous.idleSeconds();

        if (totalDelta <= 0) {
            return 0.0;
        }

        return ((totalDelta - idleDelta) / totalDelta) * 100.0;
    }

    // Sum device metric, excluding a specific device (e.g. "lo" for loopback)
    private long sumDeviceMetric(String metricsText, String metricName, String excludeDevice) {
        return parser.sumByLabel(metricsText, metricName, "device").entrySet().stream()
                .filter(e -> !excludeDevice.equals(e.getKey()))
                .mapToLong(e -> e.getValue().longValue())
                .sum();
    }

    // Sum disk metric, excluding device-mapper devices (dm-*) to avoid double-counting
    private long sumDiskMetric(String metricsText, String metricName) {
        return parser.sumByLabel(metricsText, metricName, "device").entrySet().stream()
                .filter(e -> !e.getKey().startsWith("dm-"))
                .mapToLong(e -> e.getValue().longValue())
                .sum();
    }

    record CpuReading(double totalSeconds, double idleSeconds) {}
}
