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

import java.time.Duration;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
@Profile("docker")
public class CadvisorAdapter implements CadvisorPort {

    private static final String STATS_PATH = "/api/v1.3/docker";
    private static final String DOCKER_PATH_PREFIX = "/docker/";
    private static final BigInteger UNSPECIFIED_CGROUP_MEMORY_LIMIT = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Pattern CONTAINER_ID_PATTERN = Pattern.compile("^[a-f0-9]{12,64}$");

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
                .map(e -> toResolvedEntry(e.getKey(), e.getValue()))
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        ResolvedContainerEntry::containerId,
                        e -> toContainerStats(e.info()),
                        (existing, ignored) -> existing,
                        LinkedHashMap::new
                ));
    }

    private ResolvedContainerEntry toResolvedEntry(String key, CadvisorContainerInfo info) {
        String id = resolveContainerId(key, info);
        if (id == null || id.isBlank()) {
            return null;
        }
        return new ResolvedContainerEntry(id, info);
    }

    private String resolveContainerId(String key, CadvisorContainerInfo info) {
        if (info != null && isContainerId(info.id())) {
            return info.id();
        }
        if (info != null && info.aliases() != null) {
            for (String alias : info.aliases()) {
                if (isContainerId(alias)) {
                    return alias;
                }
            }
        }
        if (key != null && key.startsWith(DOCKER_PATH_PREFIX)) {
            String candidate = key.substring(DOCKER_PATH_PREFIX.length());
            if (isContainerId(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isContainerId(String value) {
        return value != null && CONTAINER_ID_PATTERN.matcher(value).matches();
    }

    private record ResolvedContainerEntry(String containerId, CadvisorContainerInfo info) {
    }

    private Map<String, CadvisorContainerInfo> fetchStats(String baseUrl) {
        try {
            String url = baseUrl + STATS_PATH;
            return restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    null,
                    new ParameterizedTypeReference<LinkedHashMap<String, CadvisorContainerInfo>>() {}
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
                || first.cpu().usage() == null || last.cpu().usage() == null
                || first.timestamp() == null || last.timestamp() == null) {
            return 0.0;
        }

        long cpuDelta = last.cpu().usage().total() - first.cpu().usage().total();
        long timeDeltaNanos = Duration.between(first.timestamp(), last.timestamp()).toNanos();

        if (timeDeltaNanos <= 0 || cpuDelta < 0) {
            return 0.0;
        }

        return ((double) cpuDelta / timeDeltaNanos) * 100.0;
    }

    private long memoryLimit(CadvisorContainerInfo info) {
        if (info.spec() == null || info.spec().memory() == null) {
            return 0L;
        }
        BigInteger limit = info.spec().memory().limit();
        if (limit == null || limit.signum() <= 0) {
            return 0L;
        }
        if (limit.compareTo(UNSPECIFIED_CGROUP_MEMORY_LIMIT) >= 0) {
            return 0L;
        }
        return limit.longValue();
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
