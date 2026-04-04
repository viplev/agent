package dk.viplev.agent.adapter.outbound.container.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.ContainerSpec;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.LocalNodeState;
import com.github.dockerjava.api.model.Mount;
import com.github.dockerjava.api.model.MountType;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.NetworkAttachmentConfig;
import com.github.dockerjava.api.model.Service;
import com.github.dockerjava.api.model.ServiceGlobalModeOptions;
import com.github.dockerjava.api.model.ServiceModeConfig;
import com.github.dockerjava.api.model.ServiceSpec;
import com.github.dockerjava.api.model.TaskSpec;
import com.github.dockerjava.api.model.Volume;
import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@Profile("docker")
public class ExporterLifecycleManager {

    static final String NETWORK_NAME = "viplev_agent";
    static final String CADVISOR_CONTAINER_NAME = "viplev-cadvisor";
    static final String NODE_EXPORTER_CONTAINER_NAME = "viplev-node-exporter";

    private final DockerClient dockerClient;
    private final String cadvisorImage;
    private final String nodeExporterImage;

    public ExporterLifecycleManager(
            DockerClient dockerClient,
            @Value("${agent.cadvisor-image}") String cadvisorImage,
            @Value("${agent.node-exporter-image}") String nodeExporterImage) {
        this.dockerClient = dockerClient;
        this.cadvisorImage = cadvisorImage;
        this.nodeExporterImage = nodeExporterImage;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        boolean swarm = isSwarmActive();
        String networkId = ensureNetworkExists(swarm);

        if (swarm) {
            connectAgentToNetwork(networkId);
            startSwarmServiceIfAbsent(CADVISOR_CONTAINER_NAME, cadvisorImage,
                    buildCadvisorMounts(), List.of(), networkId);
            startSwarmServiceIfAbsent(NODE_EXPORTER_CONTAINER_NAME, nodeExporterImage,
                    buildNodeExporterMounts(),
                    List.of("--path.procfs=/host/proc", "--path.sysfs=/host/sys", "--path.rootfs=/rootfs"), networkId);
        } else {
            connectAgentToNetwork(networkId);
            pullImageIfAbsent(cadvisorImage);
            startContainerIfAbsent(CADVISOR_CONTAINER_NAME, cadvisorImage, buildCadvisorHostConfig(), List.of());
            pullImageIfAbsent(nodeExporterImage);
            startContainerIfAbsent(NODE_EXPORTER_CONTAINER_NAME, nodeExporterImage, buildNodeExporterHostConfig(),
                    List.of("--path.procfs=/host/proc", "--path.sysfs=/host/sys", "--path.rootfs=/rootfs"));
        }
    }

    @PreDestroy
    public void stop() {
        if (isSwarmActive()) {
            removeSwarmService(CADVISOR_CONTAINER_NAME);
            removeSwarmService(NODE_EXPORTER_CONTAINER_NAME);
        } else {
            removeContainer(CADVISOR_CONTAINER_NAME);
            removeContainer(NODE_EXPORTER_CONTAINER_NAME);
        }
        removeNetwork(NETWORK_NAME);
    }

    boolean isSwarmActive() {
        var info = dockerClient.infoCmd().exec();
        return info.getSwarm() != null
                && LocalNodeState.ACTIVE == info.getSwarm().getLocalNodeState();
    }

    private String ensureNetworkExists(boolean swarm) {
        String requiredDriver = swarm ? "overlay" : "bridge";
        try {
            var cmd = dockerClient.createNetworkCmd()
                    .withName(NETWORK_NAME)
                    .withDriver(requiredDriver);
            if (swarm) {
                cmd.withAttachable(true);
            }
            var response = cmd.exec();
            log.info("Created Docker {} network '{}'", requiredDriver, NETWORK_NAME);
            return response.getId();
        } catch (DockerException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                return validateOrRecreateNetwork(requiredDriver, swarm);
            }
            throw new ContainerRuntimeException("Failed to create Docker network " + NETWORK_NAME, e);
        }
    }

    private String validateOrRecreateNetwork(String requiredDriver, boolean swarm) {
        Network existing = findNetwork(NETWORK_NAME);
        if (existing == null) {
            throw new ContainerRuntimeException("Network '" + NETWORK_NAME + "' not found after creation conflict");
        }
        boolean driverMatches = requiredDriver.equals(existing.getDriver());
        boolean attachableMatches = !swarm || Boolean.TRUE.equals(existing.isAttachable());
        if (driverMatches && attachableMatches) {
            log.debug("Docker network '{}' already exists with correct driver '{}', reusing", NETWORK_NAME, requiredDriver);
            return existing.getId();
        }
        log.warn("Docker network '{}' exists with driver '{}' (required: '{}'), removing and recreating",
                NETWORK_NAME, existing.getDriver(), requiredDriver);
        try {
            dockerClient.removeNetworkCmd(existing.getId()).exec();
        } catch (DockerException removeEx) {
            throw new ContainerRuntimeException(
                    "Network '" + NETWORK_NAME + "' has wrong driver ('" + existing.getDriver() +
                    "' vs required '" + requiredDriver + "') and could not be removed — remove it manually and restart the agent",
                    removeEx);
        }
        var cmd = dockerClient.createNetworkCmd().withName(NETWORK_NAME).withDriver(requiredDriver);
        if (swarm) {
            cmd.withAttachable(true);
        }
        String newId = cmd.exec().getId();
        log.info("Recreated Docker {} network '{}'", requiredDriver, NETWORK_NAME);
        return newId;
    }

    private Network findNetwork(String networkName) {
        return dockerClient.listNetworksCmd()
                .withNameFilter(networkName)
                .exec()
                .stream()
                .filter(n -> networkName.equals(n.getName()))
                .findFirst()
                .orElse(null);
    }

    private void connectAgentToNetwork(String networkId) {
        try {
            String containerId = readSelfContainerId();
            dockerClient.connectToNetworkCmd()
                    .withNetworkId(networkId)
                    .withContainerId(containerId)
                    .exec();
            log.info("Connected agent container to network '{}'", NETWORK_NAME);
        } catch (DockerException e) {
            if (e.getMessage() != null && e.getMessage().contains("already exists")) {
                log.debug("Agent container already connected to network '{}'", NETWORK_NAME);
            } else {
                log.warn("Failed to connect agent container to network '{}': {}", NETWORK_NAME, e.getMessage());
            }
        }
    }

    String readSelfContainerId() {
        try {
            return Files.readString(Path.of("/etc/hostname")).trim();
        } catch (IOException e) {
            throw new ContainerRuntimeException("Failed to read agent container ID from /etc/hostname", e);
        }
    }

    private void startContainerIfAbsent(String containerName, String image, HostConfig hostConfig, List<String> command) {
        String existingId = findContainerIdByName(containerName);
        if (existingId != null) {
            var state = dockerClient.inspectContainerCmd(existingId).exec().getState();
            if (state != null && Boolean.TRUE.equals(state.getRunning())) {
                log.info("Container '{}' already exists and is running, skipping", containerName);
                return;
            }
            dockerClient.startContainerCmd(existingId).exec();
            log.info("Started existing stopped container '{}' (id: {})", containerName, existingId);
            return;
        }

        var createCmd = dockerClient.createContainerCmd(image)
                .withName(containerName)
                .withHostConfig(hostConfig);

        if (!command.isEmpty()) {
            createCmd.withCmd(command);
        }

        String containerId = createCmd.exec().getId();
        dockerClient.startContainerCmd(containerId).exec();
        log.info("Started container '{}' with image '{}' (id: {})", containerName, image, containerId);
    }

    private String findContainerIdByName(String containerName) {
        return dockerClient.listContainersCmd()
                .withNameFilter(List.of(containerName))
                .withShowAll(true)
                .exec()
                .stream()
                .filter(c -> c.getNames() != null &&
                        Arrays.stream(c.getNames()).anyMatch(name -> name.equals("/" + containerName) || name.equals(containerName)))
                .map(c -> c.getId())
                .findFirst()
                .orElse(null);
    }

    private void pullImageIfAbsent(String image) {
        try {
            dockerClient.inspectImageCmd(image).exec();
            log.debug("Image '{}' already present locally", image);
        } catch (NotFoundException e) {
            log.info("Image '{}' not found locally, pulling...", image);
            try {
                dockerClient.pullImageCmd(image)
                        .exec(new PullImageResultCallback())
                        .awaitCompletion();
                log.info("Pulled image '{}'", image);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new ContainerRuntimeException("Interrupted while pulling image " + image, ie);
            }
        }
    }


    private void removeContainer(String containerName) {
        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
            log.info("Removed container '{}'", containerName);
        } catch (Exception e) {
            log.warn("Failed to remove container '{}': {}", containerName, e.getMessage());
        }
    }

    private void removeNetwork(String networkName) {
        try {
            List<Network> networks = dockerClient.listNetworksCmd()
                    .withNameFilter(networkName)
                    .exec();
            for (Network network : networks) {
                if (networkName.equals(network.getName())) {
                    dockerClient.removeNetworkCmd(network.getId()).exec();
                    log.info("Removed Docker network '{}'", networkName);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to remove Docker network '{}': {}", networkName, e.getMessage());
        }
    }

    private void startSwarmServiceIfAbsent(String serviceName, String image, List<Mount> mounts, List<String> args, String networkId) {
        if (isSwarmServicePresent(serviceName)) {
            log.info("Swarm service '{}' already exists, skipping", serviceName);
            return;
        }

        var containerSpec = new ContainerSpec().withImage(image).withMounts(mounts);
        if (!args.isEmpty()) {
            containerSpec.withArgs(args);
        }

        // Note: Docker Swarm services do not support --privileged. Cadvisor works with
        // bind mounts alone for most metrics; some advanced disk I/O stats may be unavailable.
        var taskSpec = new TaskSpec()
                .withContainerSpec(containerSpec)
                .withNetworks(List.of(new NetworkAttachmentConfig().withTarget(networkId)));

        var serviceSpec = new ServiceSpec()
                .withName(serviceName)
                .withTaskTemplate(taskSpec)
                .withMode(new ServiceModeConfig().withGlobal(new ServiceGlobalModeOptions()));

        dockerClient.createServiceCmd(serviceSpec).exec();
        log.info("Created global Swarm service '{}' with image '{}'", serviceName, image);
    }

    private boolean isSwarmServicePresent(String serviceName) {
        return dockerClient.listServicesCmd()
                .withNameFilter(List.of(serviceName))
                .exec()
                .stream()
                .map(Service::getSpec)
                .filter(spec -> spec != null)
                .anyMatch(spec -> serviceName.equals(spec.getName()));
    }

    private void removeSwarmService(String serviceName) {
        try {
            dockerClient.removeServiceCmd(serviceName).exec();
            log.info("Removed Swarm service '{}'", serviceName);
        } catch (Exception e) {
            log.warn("Failed to remove Swarm service '{}': {}", serviceName, e.getMessage());
        }
    }

    private HostConfig buildCadvisorHostConfig() {
        return HostConfig.newHostConfig()
                .withBinds(
                        new Bind("/", new Volume("/rootfs"), AccessMode.ro),
                        new Bind("/var/run", new Volume("/var/run"), AccessMode.ro),
                        new Bind("/sys", new Volume("/sys"), AccessMode.ro),
                        new Bind("/var/lib/docker", new Volume("/var/lib/docker"), AccessMode.ro)
                )
                .withNetworkMode(NETWORK_NAME)
                .withPrivileged(true);
    }

    private HostConfig buildNodeExporterHostConfig() {
        return HostConfig.newHostConfig()
                .withBinds(
                        new Bind("/proc", new Volume("/host/proc"), AccessMode.ro),
                        new Bind("/sys", new Volume("/host/sys"), AccessMode.ro),
                        new Bind("/", new Volume("/rootfs"), AccessMode.ro)
                )
                .withNetworkMode(NETWORK_NAME);
    }

    private List<Mount> buildCadvisorMounts() {
        return List.of(
                new Mount().withType(MountType.BIND).withSource("/").withTarget("/rootfs").withReadOnly(true),
                new Mount().withType(MountType.BIND).withSource("/var/run").withTarget("/var/run").withReadOnly(true),
                new Mount().withType(MountType.BIND).withSource("/sys").withTarget("/sys").withReadOnly(true),
                new Mount().withType(MountType.BIND).withSource("/var/lib/docker").withTarget("/var/lib/docker").withReadOnly(true)
        );
    }

    private List<Mount> buildNodeExporterMounts() {
        return List.of(
                new Mount().withType(MountType.BIND).withSource("/proc").withTarget("/host/proc").withReadOnly(true),
                new Mount().withType(MountType.BIND).withSource("/sys").withTarget("/host/sys").withReadOnly(true),
                new Mount().withType(MountType.BIND).withSource("/").withTarget("/rootfs").withReadOnly(true)
        );
    }
}
