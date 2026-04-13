package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.exception.AgentException;
import dk.viplev.agent.domain.model.ContainerStartRequest;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@Profile("docker")
public class K6Service {

    static final String SCRIPT_CONTAINER_PATH = "/tmp/viplev-k6-script.js";
    private static final String SCRIPT_ENV = "VIPLEV_K6_SCRIPT_BASE64";
    private static final String STDOUT_JSON_PATH = "/dev/stdout";
    private static final Pattern SYSTEM_TAG_PATTERN = Pattern.compile("[a-z_]+");

    private final ViplevApiPort viplevApiPort;
    private final String k6Image;
    private final String k6Network;
    private final String k6SystemTags;

    @Autowired
    public K6Service(ViplevApiPort viplevApiPort,
                     @Value("${agent.k6-image}") String k6Image,
                     @Value("${agent.k6-network}") String k6Network,
                     @Value("${agent.k6-system-tags:method,url,status,group}") String k6SystemTags) {
        this.viplevApiPort = viplevApiPort;
        this.k6Image = k6Image;
        this.k6Network = k6Network;
        this.k6SystemTags = normalizeSystemTags(k6SystemTags);
    }

    K6Service(ViplevApiPort viplevApiPort,
              String k6Image,
              String k6Network) {
        this(viplevApiPort, k6Image, k6Network, "method,url,status,group");
    }

    ContainerStartRequest startRequest(String k6Instructions) {
        String encodedScript = Base64.getEncoder().encodeToString(k6Instructions.getBytes(StandardCharsets.UTF_8));
        return new ContainerStartRequest(
                k6Image,
                Map.of(SCRIPT_ENV, encodedScript),
                Map.of(),
                k6Network,
                List.of(
                        "-c",
                        "echo \"$" + SCRIPT_ENV + "\" | base64 -d > " + SCRIPT_CONTAINER_PATH
                                + " && k6 run --quiet --no-summary"
                                + systemTagsFlag()
                                + " --out json=" + STDOUT_JSON_PATH + " " + SCRIPT_CONTAINER_PATH
                ),
                List.of("sh")
        );
    }

    MetricPerformanceDTO parsePerformanceMetricsFromLogs(String k6Logs) {
        if (k6Logs == null || k6Logs.isBlank()) {
            throw new AgentException("K6 output logs were empty; no performance metrics available");
        }

        K6MetricAccumulator accumulator = new K6MetricAccumulator(Integer.MAX_VALUE);

        try (BufferedReader reader = new BufferedReader(new StringReader(k6Logs))) {
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                accumulator.acceptLine(line, lineNumber);
            }
        } catch (IOException e) {
            throw new AgentException("Failed to read K6 output logs: " + e.getMessage(), e);
        }

        MetricPerformanceDTO metrics = accumulator.drainAll();
        int httpCount = metrics.getHttpMetrics() == null ? 0 : metrics.getHttpMetrics().size();
        int vusCount = metrics.getVusMetrics() == null ? 0 : metrics.getVusMetrics().size();
        if (httpCount == 0 && vusCount == 0) {
            throw new AgentException("No K6 metrics found in output logs");
        }

        return metrics;
    }

    void sendPerformanceMetrics(UUID benchmarkId, UUID runId, MetricPerformanceDTO metrics) {
        viplevApiPort.sendPerformanceMetrics(benchmarkId, runId, metrics);
    }

    private String systemTagsFlag() {
        if (k6SystemTags == null || k6SystemTags.isBlank()) {
            return "";
        }
        return " --system-tags=" + k6SystemTags;
    }

    private String normalizeSystemTags(String configuredSystemTags) {
        if (configuredSystemTags == null || configuredSystemTags.isBlank()) {
            return "";
        }

        String[] rawTags = configuredSystemTags.split(",");
        List<String> normalizedTags = new ArrayList<>();

        for (String rawTag : rawTags) {
            String tag = rawTag == null ? "" : rawTag.trim();
            if (tag.isBlank() || !SYSTEM_TAG_PATTERN.matcher(tag).matches()) {
                throw new AgentException("Invalid agent.k6-system-tags value: " + configuredSystemTags);
            }
            normalizedTags.add(tag);
        }

        return String.join(",", normalizedTags);
    }
}
