package dk.viplev.agent.adapter.outbound.container.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerClientException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.BlkioStatEntry;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Volume;
import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import dk.viplev.agent.domain.model.ContainerEvent;
import dk.viplev.agent.domain.model.ContainerInfo;
import dk.viplev.agent.domain.model.ContainerStartRequest;
import dk.viplev.agent.domain.model.ContainerStats;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Component
@Profile("docker")
public class DockerContainerAdapter implements ContainerPort, Closeable {

    private final DockerClient dockerClient;

    private final ConcurrentHashMap<String, CpuReading> previousCpuReadings = new ConcurrentHashMap<>();
    private volatile Closeable eventStream;

    public DockerContainerAdapter(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public List<ContainerInfo> listContainers() {
        return execute("list containers", () -> {
            List<Container> containers = dockerClient.listContainersCmd().exec();
            return containers.stream()
                    .map(this::toContainerInfo)
                    .toList();
        });
    }

    @Override
    public ContainerStats getContainerStats(String containerId) {
        return execute("get container stats for " + containerId, () -> {
            Statistics statistics = fetchStatistics(containerId);
            return toContainerStats(containerId, statistics);
        });
    }

    @Override
    public String startContainer(ContainerStartRequest request) {
        return execute("start container with image " + request.imageName(), () -> {
            var createCmd = dockerClient.createContainerCmd(request.imageName());

            if (request.env() != null && !request.env().isEmpty()) {
                List<String> envList = request.env().entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .toList();
                createCmd.withEnv(envList);
            }

            if (request.command() != null && !request.command().isEmpty()) {
                createCmd.withCmd(request.command());
            }

            if (request.entrypoint() != null && !request.entrypoint().isEmpty()) {
                createCmd.withEntrypoint(request.entrypoint());
            }

            HostConfig hostConfig = HostConfig.newHostConfig();

            if (request.volumes() != null && !request.volumes().isEmpty()) {
                Bind[] binds = request.volumes().entrySet().stream()
                        .map(e -> new Bind(e.getKey(), new Volume(e.getValue())))
                        .toArray(Bind[]::new);
                hostConfig.withBinds(binds);
            }

            if (request.network() != null && !request.network().isBlank()) {
                hostConfig.withNetworkMode(request.network());
            }

            createCmd.withHostConfig(hostConfig);

            String containerId = createCmd.exec().getId();
            dockerClient.startContainerCmd(containerId).exec();

            log.debug("Started container {} with image {}", containerId, request.imageName());
            return containerId;
        });
    }

    @Override
    public void stopContainer(String containerId) {
        execute("stop container " + containerId, () -> {
            try {
                dockerClient.stopContainerCmd(containerId).exec();
                log.debug("Stopped container {}", containerId);
            } catch (NotModifiedException e) {
                log.debug("Container {} already stopped", containerId);
            }
            return null;
        });
    }

    @Override
    public void removeContainer(String containerId) {
        execute("remove container " + containerId, () -> {
            try {
                dockerClient.removeContainerCmd(containerId)
                        .withForce(true)
                        .withRemoveVolumes(true)
                        .exec();
                log.debug("Removed container {}", containerId);
            } catch (NotFoundException e) {
                log.debug("Container {} already removed", containerId);
            }
            return null;
        });
    }

    @Override
    public boolean isContainerRunning(String containerId) {
        return execute("check if container is running " + containerId, () -> {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            return inspect.getState() != null && Boolean.TRUE.equals(inspect.getState().getRunning());
        });
    }

    @Override
    public Long getContainerExitCode(String containerId) {
        return execute("get container exit code " + containerId, () -> {
            InspectContainerResponse inspect = dockerClient.inspectContainerCmd(containerId).exec();
            if (inspect.getState() == null) {
                return null;
            }
            return inspect.getState().getExitCodeLong();
        });
    }

    @Override
    public String getContainerLogs(String containerId, int maxBytes) {
        return execute("get container logs " + containerId, () -> {
            int boundedMaxBytes = Math.max(1, maxBytes);
            int tailLines = Math.max(1, Math.min(1000, boundedMaxBytes / 120));
            ByteArrayOutputStream output = new ByteArrayOutputStream();

            try {
                dockerClient.logContainerCmd(containerId)
                        .withStdOut(true)
                        .withStdErr(true)
                        .withTail(tailLines)
                        .exec(new ResultCallback.Adapter<>() {
                            @Override
                            public void onNext(Frame frame) {
                                if (frame == null || frame.getPayload() == null || frame.getPayload().length == 0) {
                                    return;
                                }

                                int remaining = boundedMaxBytes - output.size();
                                if (remaining <= 0) {
                                    return;
                                }

                                byte[] payload = frame.getPayload();
                                int bytesToWrite = Math.min(payload.length, remaining);
                                output.write(payload, 0, bytesToWrite);
                            }
                        }).awaitCompletion();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ContainerRuntimeException("Interrupted while reading logs for container " + containerId, e);
            }

            return output.toString(StandardCharsets.UTF_8);
        });
    }

    @Override
    public void watchContainerEvents(Consumer<ContainerEvent> callback) {
        execute("watch container events", () -> {
            if (eventStream != null) {
                try {
                    eventStream.close();
                } catch (IOException e) {
                    log.debug("Failed to close previous event stream", e);
                }
            }

            eventStream = dockerClient.eventsCmd()
                    .withEventTypeFilter("container")
                    .withEventFilter("start", "stop", "die")
                    .exec(new ResultCallback.Adapter<>() {
                        @Override
                        public void onNext(Event event) {
                            ContainerEvent.EventType eventType = mapEventAction(event.getAction());
                            if (eventType != null) {
                                if (event.getActor() == null || event.getActor().getId() == null) {
                                    log.debug("Received Docker event without actor for action '{}', skipping",
                                            event.getAction());
                                    return;
                                }
                                String containerId = event.getActor().getId();
                                String containerName = extractContainerName(event);

                                if (eventType == ContainerEvent.EventType.DIED
                                        || eventType == ContainerEvent.EventType.STOPPED) {
                                    previousCpuReadings.remove(containerId);
                                }

                                try {
                                    callback.accept(new ContainerEvent(
                                            containerId,
                                            containerName,
                                            eventType,
                                            Instant.ofEpochSecond(event.getTime())
                                    ));
                                } catch (Exception e) {
                                    log.error("Error in container event callback for {} ({}), event {}",
                                            containerId, containerName, eventType, e);
                                }
                            }
                        }
                    });
            log.debug("Started watching container events");
            return null;
        });
    }

    @Override
    public void close() throws IOException {
        if (eventStream != null) {
            eventStream.close();
            eventStream = null;
        }
    }

    // -- Container info mapping --

    // Note: inspectContainerCmd is called per container (N+1) to retrieve resource limits.
    // Acceptable for the expected container count; revisit if performance becomes an issue.
    private ContainerInfo toContainerInfo(Container container) {
        InspectContainerResponse inspection = dockerClient.inspectContainerCmd(container.getId()).exec();
        HostConfig hostConfig = inspection.getHostConfig();

        String name = container.getNames() != null && container.getNames().length > 0
                ? container.getNames()[0].replaceFirst("^/", "")
                : "";

        return new ContainerInfo(
                container.getId(),
                name,
                container.getImage(),
                container.getImageId(),
                container.getState(),
                hostConfig != null ? hostConfig.getNanoCPUs() : null,
                hostConfig != null ? longFromInt(hostConfig.getCpuShares()) : null,
                hostConfig != null ? hostConfig.getMemory() : null,
                hostConfig != null ? hostConfig.getMemoryReservation() : null
        );
    }

    private static Long longFromInt(Integer value) {
        return value != null ? value.longValue() : null;
    }

    // -- Container stats --

    private Statistics fetchStatistics(String containerId) {
        var result = new Statistics[1];
        var error = new Throwable[1];
        var latch = new CountDownLatch(1);

        ResultCallback.Adapter<Statistics> callback = dockerClient.statsCmd(containerId)
                .withNoStream(true)
                .exec(new ResultCallback.Adapter<>() {
                    @Override
                    public void onNext(Statistics stats) {
                        result[0] = stats;
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        error[0] = throwable;
                        latch.countDown();
                    }

                    @Override
                    public void onComplete() {
                        latch.countDown();
                    }
                });

        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new ContainerRuntimeException(
                        "Timed out waiting for stats from container " + containerId);
            }
            if (error[0] != null) {
                throw new ContainerRuntimeException(
                        "Error fetching stats for container " + containerId + ": " + error[0].getMessage(),
                        error[0]);
            }
            if (result[0] == null) {
                throw new ContainerRuntimeException(
                        "No stats received for container " + containerId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ContainerRuntimeException("Interrupted while fetching stats for container " + containerId, e);
        } finally {
            try {
                callback.close();
            } catch (IOException e) {
                log.debug("Failed to close stats callback for container {}", containerId, e);
            }
        }

        return result[0];
    }

    private ContainerStats toContainerStats(String containerId, Statistics stats) {
        double cpuPercentage = calculateContainerCpuPercentage(containerId, stats);

        long memoryUsage = stats.getMemoryStats().getUsage() != null
                ? stats.getMemoryStats().getUsage() : 0L;
        long memoryLimit = stats.getMemoryStats().getLimit() != null
                ? stats.getMemoryStats().getLimit() : 0L;

        long networkIn = 0L;
        long networkOut = 0L;
        Map<String, StatisticNetworksConfig> networks = stats.getNetworks();
        if (networks != null) {
            for (StatisticNetworksConfig net : networks.values()) {
                networkIn += net.getRxBytes() != null ? net.getRxBytes() : 0L;
                networkOut += net.getTxBytes() != null ? net.getTxBytes() : 0L;
            }
        }

        long blockIn = 0L;
        long blockOut = 0L;
        if (stats.getBlkioStats() != null
                && stats.getBlkioStats().getIoServiceBytesRecursive() != null) {
            for (BlkioStatEntry entry : stats.getBlkioStats().getIoServiceBytesRecursive()) {
                if ("read".equalsIgnoreCase(entry.getOp())) {
                    blockIn += entry.getValue() != null ? entry.getValue() : 0L;
                } else if ("write".equalsIgnoreCase(entry.getOp())) {
                    blockOut += entry.getValue() != null ? entry.getValue() : 0L;
                }
            }
        }

        return new ContainerStats(cpuPercentage, memoryUsage, memoryLimit,
                networkIn, networkOut, blockIn, blockOut);
    }

    private double calculateContainerCpuPercentage(String containerId, Statistics stats) {
        long totalCpu = stats.getCpuStats().getCpuUsage().getTotalUsage();
        long systemCpu = stats.getCpuStats().getSystemCpuUsage();

        CpuReading previous = previousCpuReadings.put(containerId, new CpuReading(totalCpu, systemCpu));
        if (previous == null) {
            return 0.0;
        }

        long cpuDelta = totalCpu - previous.totalCpuNanos();
        long systemDelta = systemCpu - previous.systemCpuNanos();

        if (systemDelta <= 0 || cpuDelta < 0) {
            return 0.0;
        }

        Long onlineCpus = stats.getCpuStats().getOnlineCpus();
        long numCpus;
        if (onlineCpus != null && onlineCpus > 0) {
            numCpus = onlineCpus;
        } else {
            var percpu = stats.getCpuStats().getCpuUsage().getPercpuUsage();
            numCpus = percpu != null && !percpu.isEmpty() ? percpu.size() : 1;
        }

        return ((double) cpuDelta / systemDelta) * numCpus * 100.0;
    }

    // -- Event mapping --

    private static ContainerEvent.EventType mapEventAction(String action) {
        return switch (action) {
            case "start" -> ContainerEvent.EventType.STARTED;
            case "stop" -> ContainerEvent.EventType.STOPPED;
            case "die" -> ContainerEvent.EventType.DIED;
            default -> null;
        };
    }

    private static String extractContainerName(Event event) {
        if (event.getActor() != null
                && event.getActor().getAttributes() != null
                && event.getActor().getAttributes().containsKey("name")) {
            return event.getActor().getAttributes().get("name");
        }
        return "";
    }

    // -- Exception wrapping --

    private <T> T execute(String operation, java.util.function.Supplier<T> action) {
        try {
            return action.get();
        } catch (ContainerRuntimeException e) {
            throw e;
        } catch (DockerException e) {
            throw new ContainerRuntimeException(
                    "Failed to " + operation + ": " + e.getMessage(), e);
        } catch (DockerClientException e) {
            throw new ContainerRuntimeException(
                    "Failed to " + operation + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ContainerRuntimeException(
                    "Failed to " + operation + ": " + e.getMessage(), e);
        }
    }

    record CpuReading(long totalCpuNanos, long systemCpuNanos) {}
}
