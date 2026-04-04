package dk.viplev.agent.adapter.outbound.metrics.nodeexporter;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PrometheusMetricsParserTest {

    private final PrometheusMetricsParser parser = new PrometheusMetricsParser();

    @Test
    void parseGaugeValue_parsesSimpleGauge() {
        String metrics = "node_memory_MemTotal_bytes 16672759808\n";

        double result = parser.parseGaugeValue(metrics, "node_memory_MemTotal_bytes");

        assertThat(result).isCloseTo(16672759808.0, within(1.0));
    }

    @Test
    void parseGaugeValue_parsesScientificNotation() {
        String metrics = "node_memory_MemAvailable_bytes 8.925474816e+09\n";

        double result = parser.parseGaugeValue(metrics, "node_memory_MemAvailable_bytes");

        assertThat(result).isCloseTo(8925474816.0, within(1.0));
    }

    @Test
    void parseGaugeValue_skipsCommentLines() {
        String metrics = """
                # HELP node_memory_MemTotal_bytes Memory information field MemTotal_bytes.
                # TYPE node_memory_MemTotal_bytes gauge
                node_memory_MemTotal_bytes 1.6672759808e+10
                """;

        double result = parser.parseGaugeValue(metrics, "node_memory_MemTotal_bytes");

        assertThat(result).isCloseTo(1.6672759808e10, within(1.0));
    }

    @Test
    void parseGaugeValue_returnsZeroWhenNotFound() {
        double result = parser.parseGaugeValue("", "node_memory_MemTotal_bytes");

        assertThat(result).isZero();
    }

    @Test
    void parseGaugeValue_doesNotMatchPrefixOfOtherMetric() {
        String metrics = "node_memory_MemTotal_bytes_extended 999\nnode_memory_MemTotal_bytes 123\n";

        double result = parser.parseGaugeValue(metrics, "node_memory_MemTotal_bytes");

        assertThat(result).isCloseTo(123.0, within(0.001));
    }

    @Test
    void parseLabeledValues_parsesCpuSecondsMultiCpuMultiMode() {
        String metrics = """
                # HELP node_cpu_seconds_total Seconds the CPUs spent in each mode.
                # TYPE node_cpu_seconds_total counter
                node_cpu_seconds_total{cpu="0",mode="idle"} 19452.53
                node_cpu_seconds_total{cpu="0",mode="iowait"} 39.3
                node_cpu_seconds_total{cpu="0",mode="system"} 1830.31
                node_cpu_seconds_total{cpu="0",mode="user"} 3046.89
                node_cpu_seconds_total{cpu="1",mode="idle"} 20100.0
                node_cpu_seconds_total{cpu="1",mode="system"} 900.0
                """;

        Map<Map<String, String>, Double> result = parser.parseLabeledValues(metrics, "node_cpu_seconds_total");

        assertThat(result).hasSize(6);
        assertThat(result.get(Map.of("cpu", "0", "mode", "idle"))).isCloseTo(19452.53, within(0.01));
        assertThat(result.get(Map.of("cpu", "0", "mode", "iowait"))).isCloseTo(39.3, within(0.01));
        assertThat(result.get(Map.of("cpu", "1", "mode", "idle"))).isCloseTo(20100.0, within(0.01));
    }

    @Test
    void parseLabeledValues_skipsCommentLines() {
        String metrics = """
                # TYPE node_cpu_seconds_total counter
                # HELP node_cpu_seconds_total Seconds the CPUs spent in each mode.
                node_cpu_seconds_total{cpu="0",mode="idle"} 100.0
                """;

        Map<Map<String, String>, Double> result = parser.parseLabeledValues(metrics, "node_cpu_seconds_total");

        assertThat(result).hasSize(1);
        assertThat(result.get(Map.of("cpu", "0", "mode", "idle"))).isCloseTo(100.0, within(0.01));
    }

    @Test
    void parseLabeledValues_returnsEmptyMapWhenNotFound() {
        Map<Map<String, String>, Double> result = parser.parseLabeledValues("", "node_cpu_seconds_total");

        assertThat(result).isEmpty();
    }

    @Test
    void sumByLabel_sumsNetworkBytesPerDevice() {
        String metrics = """
                node_network_receive_bytes_total{device="eth0",duplex=""} 100000
                node_network_receive_bytes_total{device="eth0",interface="main"} 0
                node_network_receive_bytes_total{device="lo",duplex=""} 5000
                """;

        Map<String, Double> result = parser.sumByLabel(metrics, "node_network_receive_bytes_total", "device");

        assertThat(result.get("eth0")).isCloseTo(100000.0, within(0.01));
        assertThat(result.get("lo")).isCloseTo(5000.0, within(0.01));
    }

    @Test
    void sumByLabel_sumsMultipleEntriesForSameDevice() {
        String metrics = """
                node_network_receive_bytes_total{device="eth0",mode="a"} 40000
                node_network_receive_bytes_total{device="eth0",mode="b"} 60000
                """;

        Map<String, Double> result = parser.sumByLabel(metrics, "node_network_receive_bytes_total", "device");

        assertThat(result.get("eth0")).isCloseTo(100000.0, within(0.01));
    }
}
