package dk.viplev.agent.domain.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dk.viplev.agent.domain.exception.AgentException;
import dk.viplev.agent.generated.model.MetricK6HttpDTO;
import dk.viplev.agent.generated.model.MetricK6VusDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

class K6MetricAccumulator {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxBufferedPoints;
    private final Map<HttpPointKey, HttpPointAccumulator> httpPoints = new LinkedHashMap<>();
    private final ArrayDeque<MetricK6VusDTO> vusPoints = new ArrayDeque<>();
    private int bufferedPoints;

    K6MetricAccumulator(int maxBufferedPoints) {
        this.maxBufferedPoints = maxBufferedPoints > 0 ? maxBufferedPoints : Integer.MAX_VALUE;
    }

    synchronized void acceptLine(String line, long lineNumber) {
        if (line == null) {
            return;
        }

        String trimmed = line.trim();
        if (!trimmed.startsWith("{")) {
            return;
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(trimmed);
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
                HttpPointAccumulator point = resolveHttpPoint(tags, collectedAt, localCollectedAt, lineNumber, true);
                point.httpReqDurationMs = toInt(value);
            }
            case "http_req_waiting" -> {
                HttpPointAccumulator point = resolveHttpPoint(tags, collectedAt, localCollectedAt, lineNumber, false);
                if (point != null) {
                    point.httpReqWaitingMs = toInt(value);
                }
            }
            case "data_received" -> {
                HttpPointAccumulator point = resolveHttpPoint(tags, collectedAt, localCollectedAt, lineNumber, false);
                if (point != null) {
                    point.dataReceivedByte = toInt(value);
                }
            }
            case "data_sent" -> {
                HttpPointAccumulator point = resolveHttpPoint(tags, collectedAt, localCollectedAt, lineNumber, false);
                if (point != null) {
                    point.dataSentByte = toInt(value);
                }
            }
            case "vus" -> {
                ensureBufferCapacity();
                vusPoints.addLast(new MetricK6VusDTO()
                        .collectedAt(localCollectedAt)
                        .vus(toInt(value)));
                bufferedPoints++;
            }
            default -> {
                // ignored
            }
        }
    }

    synchronized MetricPerformanceDTO drainBatch(int maxPoints) {
        int boundedMaxPoints = Math.max(1, maxPoints);
        List<MetricK6HttpDTO> httpMetrics = new ArrayList<>();
        List<HttpPointKey> readyKeys = new ArrayList<>();

        for (var entry : httpPoints.entrySet()) {
            if (httpMetrics.size() >= boundedMaxPoints) {
                break;
            }
            HttpPointAccumulator point = entry.getValue();
            if (point.httpReqDurationMs == null) {
                continue;
            }
            httpMetrics.add(point.toDto());
            readyKeys.add(entry.getKey());
        }

        for (HttpPointKey key : readyKeys) {
            httpPoints.remove(key);
            bufferedPoints--;
        }

        int remainingCapacity = boundedMaxPoints - httpMetrics.size();
        List<MetricK6VusDTO> vusMetrics = new ArrayList<>();
        while (remainingCapacity > 0 && !vusPoints.isEmpty()) {
            vusMetrics.add(vusPoints.pollFirst());
            bufferedPoints--;
            remainingCapacity--;
        }

        return new MetricPerformanceDTO()
                .httpMetrics(httpMetrics)
                .vusMetrics(vusMetrics);
    }

    synchronized MetricPerformanceDTO drainAll() {
        return drainBatch(Integer.MAX_VALUE);
    }

    synchronized void prependBatch(MetricPerformanceDTO batch) {
        if (batch == null) {
            return;
        }

        List<MetricK6HttpDTO> drainedHttpMetrics = batch.getHttpMetrics();
        if (drainedHttpMetrics != null && !drainedHttpMetrics.isEmpty()) {
            LinkedHashMap<HttpPointKey, HttpPointAccumulator> merged = new LinkedHashMap<>();
            int addedHttpPoints = 0;

            for (MetricK6HttpDTO httpMetric : drainedHttpMetrics) {
                if (httpMetric == null || httpMetric.getCollectedAt() == null
                        || httpMetric.getUrl() == null || httpMetric.getHttpMethod() == null
                        || httpMetric.getHttpStatus() == null || httpMetric.getExpectedStatus() == null) {
                    continue;
                }

                OffsetDateTime collectedAt = httpMetric.getCollectedAt().atOffset(ZoneOffset.UTC);
                HttpPointKey key = new HttpPointKey(
                        collectedAt,
                        httpMetric.getUrl(),
                        httpMetric.getHttpMethod(),
                        httpMetric.getHttpStatus(),
                        httpMetric.getExpectedStatus(),
                        httpMetric.getRequestGroup()
                );

                if (merged.putIfAbsent(key, HttpPointAccumulator.fromDto(httpMetric)) == null) {
                    addedHttpPoints++;
                }
            }

            for (Map.Entry<HttpPointKey, HttpPointAccumulator> existing : httpPoints.entrySet()) {
                merged.putIfAbsent(existing.getKey(), existing.getValue());
            }

            httpPoints.clear();
            httpPoints.putAll(merged);
            bufferedPoints += addedHttpPoints;
        }

        List<MetricK6VusDTO> drainedVusMetrics = batch.getVusMetrics();
        if (drainedVusMetrics != null && !drainedVusMetrics.isEmpty()) {
            for (int i = drainedVusMetrics.size() - 1; i >= 0; i--) {
                MetricK6VusDTO metric = drainedVusMetrics.get(i);
                if (metric == null) {
                    continue;
                }
                vusPoints.addFirst(metric);
                bufferedPoints++;
            }
        }
    }

    synchronized boolean hasAnySendableMetrics() {
        if (!vusPoints.isEmpty()) {
            return true;
        }
        return httpPoints.values().stream().anyMatch(point -> point.httpReqDurationMs != null);
    }

    private HttpPointAccumulator resolveHttpPoint(JsonNode tags,
                                                  OffsetDateTime collectedAt,
                                                  LocalDateTime localCollectedAt,
                                                  long lineNumber,
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

        HttpPointKey key = new HttpPointKey(collectedAt, url, httpMethod, httpStatus, expectedStatus, requestGroup);
        HttpPointAccumulator existing = httpPoints.get(key);
        if (existing != null) {
            return existing;
        }

        ensureBufferCapacity();
        HttpPointAccumulator created = new HttpPointAccumulator(
                localCollectedAt,
                url,
                httpMethod,
                requestGroup,
                httpStatus,
                expectedStatus
        );
        httpPoints.put(key, created);
        bufferedPoints++;
        return created;
    }

    private OffsetDateTime parseCollectedAt(JsonNode data, long lineNumber) {
        String timestamp = requiredText(data, "time", lineNumber);
        try {
            return OffsetDateTime.parse(timestamp);
        } catch (Exception e) {
            throw new AgentException("Invalid timestamp in K6 output at line " + lineNumber + ": " + timestamp, e);
        }
    }

    private MetricK6HttpDTO.HttpMethodEnum parseHttpMethod(String value, long lineNumber) {
        try {
            return MetricK6HttpDTO.HttpMethodEnum.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new AgentException("Unsupported HTTP method '" + value + "' in K6 output at line " + lineNumber, e);
        }
    }

    private String requiredText(JsonNode node, String field, long lineNumber) {
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

    private void ensureBufferCapacity() {
        if (bufferedPoints >= maxBufferedPoints) {
            throw new BufferOverflowException("K6 performance metric buffer is full");
        }
    }

    static final class BufferOverflowException extends RuntimeException {
        BufferOverflowException(String message) {
            super(message);
        }
    }

    private record HttpPointKey(
            OffsetDateTime collectedAt,
            String url,
            MetricK6HttpDTO.HttpMethodEnum httpMethod,
            Integer httpStatus,
            Integer expectedStatus,
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

        private static HttpPointAccumulator fromDto(MetricK6HttpDTO dto) {
            HttpPointAccumulator accumulator = new HttpPointAccumulator(
                    dto.getCollectedAt(),
                    dto.getUrl(),
                    dto.getHttpMethod(),
                    dto.getRequestGroup(),
                    dto.getHttpStatus(),
                    dto.getExpectedStatus()
            );
            accumulator.dataReceivedByte = dto.getDataReceivedByte();
            accumulator.dataSentByte = dto.getDataSentByte();
            accumulator.httpReqDurationMs = dto.getHttpReqDurationMs();
            accumulator.httpReqWaitingMs = dto.getHttpReqWaitingMs();
            return accumulator;
        }
    }
}
