package dk.viplev.agent.domain.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.viplev.agent.domain.exception.AgentException;
import dk.viplev.agent.domain.model.ContainerStartRequest;
import dk.viplev.agent.generated.model.MetricK6HttpDTO;
import dk.viplev.agent.generated.model.MetricK6VusDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("docker")
public class K6Service {

    static final String SCRIPT_CONTAINER_PATH = "/tmp/viplev-k6-script.js";
    static final String OUTPUT_CONTAINER_PATH = "/tmp/viplev-k6-output.json";
    private static final String SCRIPT_ENV = "VIPLEV_K6_SCRIPT_BASE64";

    private final ViplevApiPort viplevApiPort;
    private final ObjectMapper objectMapper;
    private final String k6Image;
    private final String k6Network;

    public K6Service(ViplevApiPort viplevApiPort,
                     @Value("${agent.k6-image}") String k6Image,
                     @Value("${agent.k6-network}") String k6Network) {
        this.viplevApiPort = viplevApiPort;
        this.objectMapper = new ObjectMapper();
        this.k6Image = k6Image;
        this.k6Network = k6Network;
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
                                + " && k6 run --quiet --out json=" + OUTPUT_CONTAINER_PATH + " " + SCRIPT_CONTAINER_PATH
                                + " && cat " + OUTPUT_CONTAINER_PATH
                ),
                List.of("sh")
        );
    }

    MetricPerformanceDTO parsePerformanceMetricsFromLogs(String k6Logs) {
        if (k6Logs == null || k6Logs.isBlank()) {
            throw new AgentException("K6 output logs were empty; no performance metrics available");
        }

        Map<HttpPointKey, HttpPointAccumulator> httpPoints = new HashMap<>();
        List<MetricK6VusDTO> vusMetrics = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new StringReader(k6Logs))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String trimmed = line.trim();
                if (!trimmed.startsWith("{")) {
                    continue;
                }
                parseLine(trimmed, lineNumber, httpPoints, vusMetrics);
            }
        } catch (IOException e) {
            throw new AgentException("Failed to read K6 output logs: " + e.getMessage(), e);
        }

        List<MetricK6HttpDTO> httpMetrics = httpPoints.values().stream()
                .filter(point -> point.httpReqDurationMs != null)
                .map(HttpPointAccumulator::toDto)
                .toList();

        if (httpMetrics.isEmpty() && vusMetrics.isEmpty()) {
            throw new AgentException("No K6 metrics found in output logs");
        }

        return new MetricPerformanceDTO()
                .httpMetrics(httpMetrics)
                .vusMetrics(vusMetrics);
    }

    void sendPerformanceMetrics(UUID benchmarkId, UUID runId, MetricPerformanceDTO metrics) {
        viplevApiPort.sendPerformanceMetrics(benchmarkId, runId, metrics);
    }

    private void parseLine(String line,
                           int lineNumber,
                           Map<HttpPointKey, HttpPointAccumulator> httpPoints,
                           List<MetricK6VusDTO> vusMetrics) {
        JsonNode root;
        try {
            root = objectMapper.readTree(line);
        } catch (Exception e) {
            return;
        }

        if (root == null || !"Point".equals(root.path("type").asText())) {
            return;
        }

        JsonNode data = root.path("data");
        if (data.isMissingNode()) {
            return;
        }

        String metric = textOrNull(root, "metric");
        if (metric == null || metric.isBlank()) {
            metric = textOrNull(data, "metric");
        }
        if (metric == null || metric.isBlank()) {
            return;
        }
        OffsetDateTime collectedAt = parseCollectedAt(data, lineNumber);
        LocalDateTime localCollectedAt = collectedAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        double value = data.path("value").asDouble(Double.NaN);
        if (Double.isNaN(value)) {
            throw new AgentException("Missing numeric value in K6 output at line " + lineNumber);
        }

        JsonNode tags = data.path("tags");

        switch (metric) {
            case "http_req_duration" -> {
                HttpPointAccumulator point = resolveHttpPoint(httpPoints, tags, collectedAt, localCollectedAt, lineNumber, true);
                point.httpReqDurationMs = toInt(value);
            }
            case "http_req_waiting" -> {
                HttpPointAccumulator point = resolveHttpPoint(httpPoints, tags, collectedAt, localCollectedAt, lineNumber, false);
                if (point != null) {
                    point.httpReqWaitingMs = toInt(value);
                }
            }
            case "data_received" -> {
                HttpPointAccumulator point = resolveHttpPoint(httpPoints, tags, collectedAt, localCollectedAt, lineNumber, false);
                if (point != null) {
                    point.dataReceivedByte = toInt(value);
                }
            }
            case "data_sent" -> {
                HttpPointAccumulator point = resolveHttpPoint(httpPoints, tags, collectedAt, localCollectedAt, lineNumber, false);
                if (point != null) {
                    point.dataSentByte = toInt(value);
                }
            }
            case "vus" -> vusMetrics.add(new MetricK6VusDTO()
                    .collectedAt(localCollectedAt)
                    .vus(toInt(value)));
            default -> {
                // ignored
            }
        }
    }

    private HttpPointAccumulator resolveHttpPoint(Map<HttpPointKey, HttpPointAccumulator> httpPoints,
                                                  JsonNode tags,
                                                  OffsetDateTime collectedAt,
                                                  LocalDateTime localCollectedAt,
                                                  int lineNumber,
                                                  boolean strict) {
        if (tags == null || tags.isMissingNode()) {
            if (strict) {
                throw new AgentException("Missing tags in K6 HTTP metric at line " + lineNumber);
            }
            return null;
        }

        String url = textOrNull(tags, "url");
        String methodValue = textOrNull(tags, "method");
        Integer httpStatus = intOrNull(tags, "status");

        if (strict && (url == null || methodValue == null || httpStatus == null)) {
            throw new AgentException("Missing required HTTP tags in K6 output at line " + lineNumber);
        }

        if (url == null || methodValue == null || httpStatus == null) {
            return null;
        }

        MetricK6HttpDTO.HttpMethodEnum httpMethod = parseHttpMethod(methodValue, lineNumber);
        Integer expectedStatus = intOrNull(tags, "expected_status");
        if (expectedStatus == null) {
            expectedStatus = httpStatus;
        }
        String requestGroup = textOrNull(tags, "group");
        Integer finalExpectedStatus = expectedStatus;
        String finalRequestGroup = requestGroup;

        HttpPointKey key = new HttpPointKey(collectedAt, url, httpMethod, httpStatus, finalRequestGroup);

        return httpPoints.computeIfAbsent(key, ignored -> new HttpPointAccumulator(
                localCollectedAt,
                url,
                httpMethod,
                finalRequestGroup,
                httpStatus,
                finalExpectedStatus
        ));
    }

    private OffsetDateTime parseCollectedAt(JsonNode data, int lineNumber) {
        String timestamp = requiredText(data, "time", lineNumber);
        try {
            return OffsetDateTime.parse(timestamp);
        } catch (Exception e) {
            throw new AgentException("Invalid timestamp in K6 output at line " + lineNumber + ": " + timestamp, e);
        }
    }

    private MetricK6HttpDTO.HttpMethodEnum parseHttpMethod(String value, int lineNumber) {
        try {
            return MetricK6HttpDTO.HttpMethodEnum.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AgentException("Unsupported HTTP method '" + value + "' in K6 output at line " + lineNumber, e);
        }
    }

    private String requiredText(JsonNode node, String field, int lineNumber) {
        String value = textOrNull(node, field);
        if (value == null || value.isBlank()) {
            throw new AgentException("Missing field '" + field + "' in K6 output at line " + lineNumber);
        }
        return value;
    }

    private String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        return value.asText(null);
    }

    private Integer intOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }

        if (value.isNumber()) {
            return value.intValue();
        }

        try {
            return Integer.parseInt(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int toInt(double value) {
        long rounded = Math.round(value);
        if (rounded > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (rounded < Integer.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) rounded;
    }

    private record HttpPointKey(
            OffsetDateTime collectedAt,
            String url,
            MetricK6HttpDTO.HttpMethodEnum httpMethod,
            Integer httpStatus,
            String requestGroup) {
    }

    private static final class HttpPointAccumulator {
        private final LocalDateTime collectedAt;
        private final String url;
        private final MetricK6HttpDTO.HttpMethodEnum httpMethod;
        private final String requestGroup;
        private final Integer httpStatus;
        private final Integer expectedStatus;
        private Integer dataReceivedByte;
        private Integer dataSentByte;
        private Integer httpReqDurationMs;
        private Integer httpReqWaitingMs;

        private HttpPointAccumulator(LocalDateTime collectedAt,
                                     String url,
                                     MetricK6HttpDTO.HttpMethodEnum httpMethod,
                                     String requestGroup,
                                     Integer httpStatus,
                                     Integer expectedStatus) {
            this.collectedAt = collectedAt;
            this.url = url;
            this.httpMethod = httpMethod;
            this.requestGroup = requestGroup;
            this.httpStatus = httpStatus;
            this.expectedStatus = expectedStatus;
        }

        private MetricK6HttpDTO toDto() {
            return new MetricK6HttpDTO()
                    .collectedAt(collectedAt)
                    .url(url)
                    .httpMethod(httpMethod)
                    .requestGroup(requestGroup)
                    .httpStatus(httpStatus)
                    .expectedStatus(expectedStatus)
                    .dataReceivedByte(dataReceivedByte != null ? dataReceivedByte : 0)
                    .dataSentByte(dataSentByte != null ? dataSentByte : 0)
                    .httpReqDurationMs(httpReqDurationMs)
                    .httpReqWaitingMs(httpReqWaitingMs != null ? httpReqWaitingMs : 0);
        }
    }
}
