package dk.viplev.agent.adapter.outbound.metrics.cadvisor;

import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import dk.viplev.agent.domain.model.ContainerStats;
import dk.viplev.agent.port.outbound.metrics.CadvisorPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@Profile("docker")
public class CadvisorAdapter implements CadvisorPort {

    private static final String STATS_PATH = "/api/v2.0/stats?type=docker&count=2";
    private static final String DOCKER_PATH_PREFIX = "/docker/";

    private final RestTemplate restTemplate;

    public CadvisorAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public Map<String, ContainerStats> scrapeAllContainerStats(String baseUrl) {
        Map<String, CadvisorContainerInfo> response = fetchStats(baseUrl);
        if (response == null) {
            return Map.of();
        }

        return response.entrySet().stream()
                .filter(e -> e.getKey().startsWith(DOCKER_PATH_PREFIX))
                .collect(Collectors.toMap(
                        e -> e.getKey().substring(DOCKER_PATH_PREFIX.length()),
                        e -> toContainerStats(e.getValue())
                ));
    }

    private Map<String, CadvisorContainerInfo> fetchStats(String baseUrl) {
        try {
            String url = baseUrl + STATS_PATH;
            return restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<Map<String, CadvisorContainerInfo>>() {}
            ).getBody();
        } catch (RestClientException e) {
            throw new ContainerRuntimeException(
                    "Failed to scrape cadvisor at " + baseUrl + ": " + e.getMessage(), e);
        }
    }

    private ContainerStats toContainerStats(CadvisorContainerInfo info) {
        List<CadvisorContainerInfo.CadvisorStat> stats = info.stats();
        if (stats == null || stats.isEmpty()) {
            return new ContainerStats(0.0, 0L, memoryLimit(info), 0L, 0L, 0L, 0L);
        }

        CadvisorContainerInfo.CadvisorStat latest = stats.getLast();

        double cpuPercentage = calculateCpuPercentage(stats);
        long memoryUsage = latest.memory() != null ? latest.memory().usage() : 0L;
        long memoryLimit = memoryLimit(info);
        long networkIn = sumNetworkBytes(latest, true);
        long networkOut = sumNetworkBytes(latest, false);
        long blockIn = sumDiskBytes(latest, "Read");
        long blockOut = sumDiskBytes(latest, "Write");

        return new ContainerStats(cpuPercentage, memoryUsage, memoryLimit,
                networkIn, networkOut, blockIn, blockOut);
    }

    private double calculateCpuPercentage(List<CadvisorContainerInfo.CadvisorStat> stats) {
        if (stats.size() < 2) {
            return 0.0;
        }
        CadvisorContainerInfo.CadvisorStat first = stats.getFirst();
        CadvisorContainerInfo.CadvisorStat last = stats.getLast();

        if (first.cpu() == null || last.cpu() == null
                || first.timestamp() == null || last.timestamp() == null) {
            return 0.0;
        }

        long cpuDelta = last.cpu().usage().total() - first.cpu().usage().total();
        long timeDelta = last.timestamp().toEpochMilli() - first.timestamp().toEpochMilli();
        long timeDeltaNanos = timeDelta * 1_000_000L;

        if (timeDeltaNanos <= 0 || cpuDelta < 0) {
            return 0.0;
        }

        return ((double) cpuDelta / timeDeltaNanos) * 100.0;
    }

    private long memoryLimit(CadvisorContainerInfo info) {
        if (info.spec() == null || info.spec().memory() == null) {
            return 0L;
        }
        return info.spec().memory().limit();
    }

    private long sumNetworkBytes(CadvisorContainerInfo.CadvisorStat stat, boolean rx) {
        if (stat.network() == null || stat.network().interfaces() == null) {
            return 0L;
        }
        return stat.network().interfaces().stream()
                .filter(iface -> !"lo".equals(iface.name()))
                .mapToLong(iface -> rx ? iface.rxBytes() : iface.txBytes())
                .sum();
    }

    private long sumDiskBytes(CadvisorContainerInfo.CadvisorStat stat, String op) {
        if (stat.diskio() == null || stat.diskio().ioServiceBytes() == null) {
            return 0L;
        }
        return stat.diskio().ioServiceBytes().stream()
                .filter(entry -> entry.stats() != null)
                .mapToLong(entry -> entry.stats().getOrDefault(op, 0L))
                .sum();
    }
}
