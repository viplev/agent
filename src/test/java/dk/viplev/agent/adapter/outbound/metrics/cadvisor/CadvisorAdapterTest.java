package dk.viplev.agent.adapter.outbound.metrics.cadvisor;

import dk.viplev.agent.domain.model.ContainerStats;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CadvisorAdapterTest {

    private static final String BASE_URL = "http://viplev-cadvisor:8080";
    private static final String CONTAINER_ID = "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039";

    @Mock
    private RestTemplate restTemplate;

    private CadvisorAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new CadvisorAdapter(restTemplate);
    }

    @Test
    void scrapeAllContainerStats_mapsAllSevenFields() {
        // Two samples 1 second apart. CPU delta = 1e9 ns over 1e9 ns time → 100%
        String json = """
                {
                  "/system.slice/docker-43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039.scope": {
                    "id": "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039",
                    "aliases": ["nginx", "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039"],
                    "spec": { "memory": { "limit": 536870912 } },
                    "stats": [
                      {
                        "timestamp": "2024-01-01T12:00:00.000000000Z",
                        "cpu": { "usage": { "total": 1000000000 } },
                        "memory": { "usage": 104857600 },
                        "network": {
                          "interfaces": [
                            { "name": "eth0", "rx_bytes": 100000, "tx_bytes": 200000 },
                            { "name": "lo",   "rx_bytes": 9999,   "tx_bytes": 9999 }
                          ]
                        },
                        "diskio": {
                          "io_service_bytes": [
                            { "stats": { "Read": 500000000, "Write": 300000000 } }
                          ]
                        }
                      },
                      {
                        "timestamp": "2024-01-01T12:00:01.000000000Z",
                        "cpu": { "usage": { "total": 2000000000 } },
                        "memory": { "usage": 209715200 },
                        "network": {
                          "interfaces": [
                            { "name": "eth0", "rx_bytes": 150000, "tx_bytes": 250000 },
                            { "name": "lo",   "rx_bytes": 9999,   "tx_bytes": 9999 }
                          ]
                        },
                        "diskio": {
                          "io_service_bytes": [
                            { "stats": { "Read": 600000000, "Write": 400000000 } }
                          ]
                        }
                      }
                    ]
                  }
                }
                """;
        mockRestTemplate(json);

        Map<String, ContainerStats> result = adapter.scrapeAllContainerStats(BASE_URL);

        assertThat(result).containsKey(CONTAINER_ID);
        ContainerStats stats = result.get(CONTAINER_ID);

        // cpuDelta=1e9 ns, timeDelta=1000ms=1e9 ns → (1e9/1e9)*100 = 100%
        assertThat(stats.cpuPercentage()).isCloseTo(100.0, within(0.01));
        // Memory from latest sample
        assertThat(stats.memoryUsageBytes()).isEqualTo(209_715_200L);
        assertThat(stats.memoryLimitBytes()).isEqualTo(536_870_912L);
        // Network: latest sample, excluding lo
        assertThat(stats.networkInBytes()).isEqualTo(150_000L);
        assertThat(stats.networkOutBytes()).isEqualTo(250_000L);
        // Disk: latest sample
        assertThat(stats.blockInBytes()).isEqualTo(600_000_000L);
        assertThat(stats.blockOutBytes()).isEqualTo(400_000_000L);
    }

    @Test
    void scrapeAllContainerStats_cpuIsZeroWithOneSample() {
        String json = """
                {
                  "/system.slice/docker-43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039.scope": {
                    "id": "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039",
                    "aliases": ["nginx", "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039"],
                    "spec": { "memory": { "limit": 0 } },
                    "stats": [
                      {
                        "timestamp": "2024-01-01T12:00:00.000000000Z",
                        "cpu": { "usage": { "total": 5000000000 } },
                        "memory": { "usage": 52428800 },
                        "network": { "interfaces": [] },
                        "diskio": { "io_service_bytes": [] }
                      }
                    ]
                  }
                }
                """;
        mockRestTemplate(json);

        Map<String, ContainerStats> result = adapter.scrapeAllContainerStats(BASE_URL);

        assertThat(result.get(CONTAINER_ID).cpuPercentage()).isZero();
    }

    @Test
    void scrapeAllContainerStats_excludesLoopbackNetwork() {
        String json = """
                {
                  "/system.slice/docker-43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039.scope": {
                    "id": "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039",
                    "aliases": ["nginx", "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039"],
                    "spec": { "memory": { "limit": 0 } },
                    "stats": [
                      {
                        "timestamp": "2024-01-01T12:00:00Z",
                        "cpu": { "usage": { "total": 0 } },
                        "memory": { "usage": 0 },
                        "network": {
                          "interfaces": [
                            { "name": "eth0", "rx_bytes": 80000, "tx_bytes": 40000 },
                            { "name": "lo",   "rx_bytes": 99999, "tx_bytes": 99999 }
                          ]
                        },
                        "diskio": { "io_service_bytes": [] }
                      }
                    ]
                  }
                }
                """;
        mockRestTemplate(json);

        ContainerStats stats = adapter.scrapeAllContainerStats(BASE_URL).get(CONTAINER_ID);

        assertThat(stats.networkInBytes()).isEqualTo(80_000L);
        assertThat(stats.networkOutBytes()).isEqualTo(40_000L);
    }

    @Test
    void scrapeAllContainerStats_excludesNonDockerContainers() {
        String json = """
                {
                  "/system.slice/docker-43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039.scope": {
                    "id": "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039",
                    "aliases": ["nginx", "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039"],
                    "spec": { "memory": { "limit": 0 } },
                    "stats": [{ "timestamp": "2024-01-01T12:00:00Z",
                                "cpu": { "usage": { "total": 0 } },
                                "memory": { "usage": 0 },
                                "network": { "interfaces": [] },
                                "diskio": { "io_service_bytes": [] } }]
                  },
                  "/": {
                    "id": "",
                    "aliases": ["root"],
                    "spec": { "memory": { "limit": 0 } },
                    "stats": [{ "timestamp": "2024-01-01T12:00:00Z",
                                "cpu": { "usage": { "total": 0 } },
                                "memory": { "usage": 0 },
                                "network": { "interfaces": [] },
                                "diskio": { "io_service_bytes": [] } }]
                  }
                }
                """;
        mockRestTemplate(json);

        Map<String, ContainerStats> result = adapter.scrapeAllContainerStats(BASE_URL);

        assertThat(result).containsOnlyKeys(CONTAINER_ID);
    }

    @Test
    void scrapeAllContainerStats_memoryLimitZeroWhenUnset() {
        String json = """
                {
                  "/system.slice/docker-43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039.scope": {
                    "id": "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039",
                    "aliases": ["nginx", "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039"],
                    "spec": { "memory": { "limit": 0 } },
                    "stats": [{ "timestamp": "2024-01-01T12:00:00Z",
                                "cpu": { "usage": { "total": 0 } },
                                "memory": { "usage": 12345678 },
                                "network": { "interfaces": [] },
                                "diskio": { "io_service_bytes": [] } }]
                  }
                }
                """;
        mockRestTemplate(json);

        assertThat(adapter.scrapeAllContainerStats(BASE_URL).get(CONTAINER_ID).memoryLimitBytes()).isZero();
    }

    @Test
    void scrapeAllContainerStats_memoryLimitZeroWhenUnlimitedSentinel() {
        String json = """
                {
                  "/system.slice/docker-43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039.scope": {
                    "id": "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039",
                    "aliases": ["nginx", "43fba5afb3a841531c2e2c330a510d997f139c90d1de14c7ec437403c23cd039"],
                    "spec": { "memory": { "limit": 18446744073709551615 } },
                    "stats": [{ "timestamp": "2024-01-01T12:00:00Z",
                                "cpu": { "usage": { "total": 0 } },
                                "memory": { "usage": 12345678 },
                                "network": { "interfaces": [] },
                                "diskio": { "io_service_bytes": [] } }]
                  }
                }
                """;
        mockRestTemplate(json);

        assertThat(adapter.scrapeAllContainerStats(BASE_URL).get(CONTAINER_ID).memoryLimitBytes()).isZero();
    }

    // -- Helper --

    @SuppressWarnings("unchecked")
    private void mockRestTemplate(String json) {
        // CadvisorAdapter uses exchange() with ParameterizedTypeReference.
        // We return null body here and rely on the real Jackson deserialization path
        // by using a lightweight approach: stub exchange to return a parsed response
        // via a real ObjectMapper.
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

            java.lang.reflect.Type type = new ParameterizedTypeReference<
                    Map<String, CadvisorContainerInfo>>() {}.getType();

            Map<String, CadvisorContainerInfo> parsed = mapper.readValue(json,
                    mapper.getTypeFactory().constructType(type));

            when(restTemplate.exchange(
                    eq(BASE_URL + "/api/v1.3/docker"),
                    eq(HttpMethod.GET),
                    isNull(),
                    any(ParameterizedTypeReference.class))
            ).thenReturn(ResponseEntity.ok(parsed));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
