package dk.viplev.agent.adapter.outbound.metrics.nodeexporter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight parser for Prometheus text exposition format.
 * Only handles the subset of metrics needed by the node_exporter scraper.
 */
class PrometheusMetricsParser {

    private static final Pattern LABEL_PATTERN = Pattern.compile("(\\w+)=\"([^\"]*)\"");

    /**
     * Parse a simple gauge metric without labels.
     * Example: {@code node_memory_MemTotal_bytes 1.6672759808e+10}
     *
     * @return parsed value, or 0.0 if not found
     */
    double parseGaugeValue(String metricsText, String metricName) {
        return metricsText.lines()
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.startsWith(metricName + " ") || line.startsWith(metricName + "\t"))
                .findFirst()
                .map(line -> {
                    String valueStr = line.substring(metricName.length()).trim();
                    // Handle optional timestamp after value (split on whitespace, take first token)
                    int spaceIdx = valueStr.indexOf(' ');
                    if (spaceIdx >= 0) {
                        valueStr = valueStr.substring(0, spaceIdx);
                    }
                    try {
                        return Double.parseDouble(valueStr);
                    } catch (NumberFormatException e) {
                        return 0.0;
                    }
                })
                .orElse(0.0);
    }

    /**
     * Parse a labeled metric into a map of label-set → value.
     * Example: {@code node_cpu_seconds_total{cpu="0",mode="idle"} 19452.53}
     * Returns: {@code {{"cpu":"0","mode":"idle"} → 19452.53, ...}}
     */
    Map<Map<String, String>, Double> parseLabeledValues(String metricsText, String metricName) {
        Map<Map<String, String>, Double> result = new LinkedHashMap<>();
        metricsText.lines()
                .filter(line -> !line.startsWith("#"))
                .filter(line -> line.startsWith(metricName + "{"))
                .forEach(line -> {
                    int braceStart = line.indexOf('{');
                    int braceEnd = line.indexOf('}');
                    if (braceStart < 0 || braceEnd < 0) return;

                    Map<String, String> labels = parseLabels(line.substring(braceStart + 1, braceEnd));
                    String valueStr = line.substring(braceEnd + 1).trim();
                    // Handle optional timestamp
                    int spaceIdx = valueStr.indexOf(' ');
                    if (spaceIdx >= 0) {
                        valueStr = valueStr.substring(0, spaceIdx);
                    }
                    try {
                        result.put(labels, Double.parseDouble(valueStr));
                    } catch (NumberFormatException e) {
                        // skip malformed values (e.g. +Inf, NaN, empty)
                    }
                });
        return result;
    }

    /**
     * Sum a labeled metric's values grouped by a single label.
     * Example: sumByLabel(text, "node_network_receive_bytes_total", "device")
     * Returns: {@code {"eth0" → 100991.0, "lo" → 0.0}}
     */
    Map<String, Double> sumByLabel(String metricsText, String metricName, String labelName) {
        Map<String, Double> result = new LinkedHashMap<>();
        parseLabeledValues(metricsText, metricName).forEach((labels, value) -> {
            String labelValue = labels.getOrDefault(labelName, "");
            result.merge(labelValue, value, Double::sum);
        });
        return result;
    }

    private Map<String, String> parseLabels(String labelsStr) {
        Map<String, String> labels = new LinkedHashMap<>();
        Matcher matcher = LABEL_PATTERN.matcher(labelsStr);
        while (matcher.find()) {
            labels.put(matcher.group(1), matcher.group(2));
        }
        return labels;
    }
}
