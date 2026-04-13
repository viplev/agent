package dk.viplev.agent.domain.services;

import dk.viplev.agent.domain.exception.AgentException;
import dk.viplev.agent.domain.model.ContainerStartRequest;
import dk.viplev.agent.generated.model.MetricK6HttpDTO;
import dk.viplev.agent.generated.model.MetricPerformanceDTO;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class K6ServiceTest {

    private final ViplevApiPort viplevApiPort = mock(ViplevApiPort.class);
    private final K6Service service = new K6Service(viplevApiPort, "grafana/k6:latest", "viplev_agent");

    @Test
    void startRequest_buildsStreamingK6CommandWithSystemTags() {
        ContainerStartRequest request = service.startRequest("export default function() {}");

        assertThat(request.command().get(1)).contains("k6 run --quiet --no-summary");
        assertThat(request.command().get(1)).contains("--system-tags=method,url,status,group");
        assertThat(request.command().get(1)).contains("--out json=/dev/stdout");
        assertThat(request.command().get(1)).doesNotContain("cat /tmp/viplev-k6-output.json");
    }

    @Test
    void startRequest_normalizesWhitespaceInConfiguredSystemTags() {
        K6Service spacedTagsService = new K6Service(viplevApiPort,
                "grafana/k6:latest",
                "viplev_agent",
                "method, url, status");

        ContainerStartRequest request = spacedTagsService.startRequest("export default function() {}");

        assertThat(request.command().get(1)).contains("--system-tags=method,url,status");
    }

    @Test
    void constructor_whenSystemTagsContainInvalidCharacters_throwsAgentException() {
        assertThatThrownBy(() -> new K6Service(viplevApiPort,
                "grafana/k6:latest",
                "viplev_agent",
                "method;rm -rf /"))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("agent.k6-system-tags");
    }

    @Test
    void parsePerformanceMetricsFromLogs_parsesHttpAndVusMetrics() {
        String logs = String.join("\n",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":205.1,\"metric\":\"http_req_duration\",\"tags\":{\"url\":\"https://api.example.com/users\",\"method\":\"GET\",\"status\":\"200\",\"expected_status\":\"200\",\"group\":\"users\"}}}",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":150.0,\"metric\":\"http_req_waiting\",\"tags\":{\"url\":\"https://api.example.com/users\",\"method\":\"GET\",\"status\":\"200\",\"expected_status\":\"200\",\"group\":\"users\"}}}",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":1024.0,\"metric\":\"data_received\",\"tags\":{\"url\":\"https://api.example.com/users\",\"method\":\"GET\",\"status\":\"200\",\"expected_status\":\"200\",\"group\":\"users\"}}}",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":256.0,\"metric\":\"data_sent\",\"tags\":{\"url\":\"https://api.example.com/users\",\"method\":\"GET\",\"status\":\"200\",\"expected_status\":\"200\",\"group\":\"users\"}}}",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:01Z\",\"value\":8,\"metric\":\"vus\",\"tags\":{}}}"
        );

        MetricPerformanceDTO result = service.parsePerformanceMetricsFromLogs(logs);

        assertThat(result.getHttpMetrics()).hasSize(1);
        var http = result.getHttpMetrics().getFirst();
        assertThat(http.getUrl()).isEqualTo("https://api.example.com/users");
        assertThat(http.getHttpMethod()).isEqualTo(MetricK6HttpDTO.HttpMethodEnum.GET);
        assertThat(http.getHttpStatus()).isEqualTo(200);
        assertThat(http.getExpectedStatus()).isEqualTo(200);
        assertThat(http.getHttpReqDurationMs()).isEqualTo(205);
        assertThat(http.getHttpReqWaitingMs()).isEqualTo(150);
        assertThat(http.getDataReceivedByte()).isEqualTo(1024);
        assertThat(http.getDataSentByte()).isEqualTo(256);

        assertThat(result.getVusMetrics()).hasSize(1);
        assertThat(result.getVusMetrics().getFirst().getVus()).isEqualTo(8);
    }

    @Test
    void parsePerformanceMetricsFromLogs_whenNoJsonPoints_throwsAgentException() {
        String logs = "some text\nnot json\nsummary";

        assertThatThrownBy(() -> service.parsePerformanceMetricsFromLogs(logs))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("No K6 metrics found");
    }

    @Test
    void parsePerformanceMetricsFromLogs_ignoresJsonLinesWithoutMetricField() {
        String logs = String.join("\n",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":205.1,\"metric\":\"http_req_duration\",\"tags\":{\"url\":\"https://api.example.com/users\",\"method\":\"GET\",\"status\":\"200\"}}}",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":1,\"tags\":{\"status\":\"200\"}}}"
        );

        MetricPerformanceDTO result = service.parsePerformanceMetricsFromLogs(logs);

        assertThat(result.getHttpMetrics()).hasSize(1);
    }

    @Test
    void parsePerformanceMetricsFromLogs_keepsSeparatePointsForDifferentExpectedStatus() {
        String logs = String.join("\n",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":200.0,\"metric\":\"http_req_duration\",\"tags\":{\"url\":\"https://api.example.com/users\",\"method\":\"GET\",\"status\":\"200\",\"expected_status\":\"200\"}}}",
                "{\"type\":\"Point\",\"data\":{\"time\":\"2026-01-01T10:00:00Z\",\"value\":201.0,\"metric\":\"http_req_duration\",\"tags\":{\"url\":\"https://api.example.com/users\",\"method\":\"GET\",\"status\":\"200\",\"expected_status\":\"201\"}}}"
        );

        MetricPerformanceDTO result = service.parsePerformanceMetricsFromLogs(logs);

        assertThat(result.getHttpMetrics()).hasSize(2);
        assertThat(result.getHttpMetrics())
                .extracting(MetricK6HttpDTO::getExpectedStatus)
                .containsExactlyInAnyOrder(200, 201);
    }
}
