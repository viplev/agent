package dk.viplev.agent.adapter.outbound.container.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.EventsCmd;
import com.github.dockerjava.api.command.InspectContainerCmd;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.ListContainersCmd;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.command.StatsCmd;
import com.github.dockerjava.api.command.StopContainerCmd;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.model.BlkioStatEntry;
import com.github.dockerjava.api.model.BlkioStatsConfig;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.CpuStatsConfig;
import com.github.dockerjava.api.model.CpuUsageConfig;
import com.github.dockerjava.api.model.Event;
import com.github.dockerjava.api.model.EventActor;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.MemoryStatsConfig;
import com.github.dockerjava.api.model.StatisticNetworksConfig;
import com.github.dockerjava.api.model.Statistics;
import dk.viplev.agent.domain.exception.ContainerRuntimeException;
import dk.viplev.agent.domain.model.ContainerEvent;
import dk.viplev.agent.domain.model.ContainerStartRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DockerContainerAdapterTest {

    @Mock
    private DockerClient dockerClient;

    private DockerContainerAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new DockerContainerAdapter(dockerClient);
    }

    @Test
    void listContainers_returnsMappedContainerInfoList() {
        var container = mock(Container.class);
        when(container.getId()).thenReturn("abc123");
        when(container.getNames()).thenReturn(new String[]{"/my-app"});
        when(container.getImage()).thenReturn("nginx:latest");
        when(container.getImageId()).thenReturn("sha256:abc");
        when(container.getState()).thenReturn("running");

        var hostConfig = mock(HostConfig.class);
        when(hostConfig.getNanoCPUs()).thenReturn(1000000000L);
        when(hostConfig.getCpuShares()).thenReturn(512);
        when(hostConfig.getMemory()).thenReturn(536870912L);
        when(hostConfig.getMemoryReservation()).thenReturn(268435456L);

        var inspection = mock(InspectContainerResponse.class);
        when(inspection.getHostConfig()).thenReturn(hostConfig);

        var listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenReturn(List.of(container));

        var inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("abc123")).thenReturn(inspectCmd);
        when(inspectCmd.exec()).thenReturn(inspection);

        var result = adapter.listContainers();

        assertThat(result).hasSize(1);
        var info = result.get(0);
        assertThat(info.id()).isEqualTo("abc123");
        assertThat(info.name()).isEqualTo("my-app");
        assertThat(info.imageName()).isEqualTo("nginx:latest");
        assertThat(info.imageSha()).isEqualTo("sha256:abc");
        assertThat(info.status()).isEqualTo("running");
        assertThat(info.cpuLimit()).isEqualTo(1000000000L);
        assertThat(info.cpuReservation()).isEqualTo(512L);
        assertThat(info.memoryLimit()).isEqualTo(536870912L);
        assertThat(info.memoryReservation()).isEqualTo(268435456L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getContainerStats_firstCallReturnsZeroCpu() {
        mockStatsCmd("container1", createStatistics(
                100_000_000L, 1_000_000_000L, 4L,
                104857600L, 536870912L,
                Map.of("eth0", networkConfig(1000L, 2000L)),
                List.of(blkioEntry("read", 500L), blkioEntry("write", 300L))
        ));

        var stats = adapter.getContainerStats("container1");

        assertThat(stats.cpuPercentage()).isEqualTo(0.0);
        assertThat(stats.memoryUsageBytes()).isEqualTo(104857600L);
        assertThat(stats.memoryLimitBytes()).isEqualTo(536870912L);
        assertThat(stats.networkInBytes()).isEqualTo(1000L);
        assertThat(stats.networkOutBytes()).isEqualTo(2000L);
        assertThat(stats.blockInBytes()).isEqualTo(500L);
        assertThat(stats.blockOutBytes()).isEqualTo(300L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getContainerStats_calculatesCorrectCpuPercentage() {
        // First call: baseline
        mockStatsCmd("container1", createStatistics(
                100_000_000L, 1_000_000_000L, 4L,
                104857600L, 536870912L,
                Map.of(), List.of()
        ));
        adapter.getContainerStats("container1");

        // Second call: 50_000_000 cpu delta out of 500_000_000 system delta, 4 CPUs
        // CPU% = (50_000_000 / 500_000_000) * 4 * 100 = 40.0%
        mockStatsCmd("container1", createStatistics(
                150_000_000L, 1_500_000_000L, 4L,
                104857600L, 536870912L,
                Map.of(), List.of()
        ));
        var stats = adapter.getContainerStats("container1");

        assertThat(stats.cpuPercentage()).isCloseTo(40.0, within(0.01));
    }

    @Test
    @SuppressWarnings("unchecked")
    void getContainerStats_aggregatesNetworkStats() {
        mockStatsCmd("container1", createStatistics(
                0L, 0L, 1L,
                0L, 0L,
                Map.of(
                        "eth0", networkConfig(1000L, 2000L),
                        "eth1", networkConfig(3000L, 4000L)
                ),
                List.of()
        ));

        var stats = adapter.getContainerStats("container1");

        assertThat(stats.networkInBytes()).isEqualTo(4000L);
        assertThat(stats.networkOutBytes()).isEqualTo(6000L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void getContainerStats_aggregatesBlockIoStats() {
        mockStatsCmd("container1", createStatistics(
                0L, 0L, 1L,
                0L, 0L,
                Map.of(),
                List.of(
                        blkioEntry("read", 1000L),
                        blkioEntry("read", 2000L),
                        blkioEntry("write", 500L),
                        blkioEntry("write", 700L)
                )
        ));

        var stats = adapter.getContainerStats("container1");

        assertThat(stats.blockInBytes()).isEqualTo(3000L);
        assertThat(stats.blockOutBytes()).isEqualTo(1200L);
    }

    @Test
    void startContainer_createsAndStartsContainer() {
        var createCmd = mock(CreateContainerCmd.class);
        when(dockerClient.createContainerCmd("nginx:latest")).thenReturn(createCmd);
        when(createCmd.withEnv(any(List.class))).thenReturn(createCmd);
        when(createCmd.withCmd(any(List.class))).thenReturn(createCmd);
        when(createCmd.withHostConfig(any(HostConfig.class))).thenReturn(createCmd);

        var response = mock(CreateContainerResponse.class);
        when(response.getId()).thenReturn("new-container-id");
        when(createCmd.exec()).thenReturn(response);

        var startCmd = mock(StartContainerCmd.class);
        when(dockerClient.startContainerCmd("new-container-id")).thenReturn(startCmd);

        var request = new ContainerStartRequest(
                "nginx:latest",
                Map.of("ENV_KEY", "value"),
                Map.of("/host/path", "/container/path"),
                "my-network",
                List.of("nginx", "-g", "daemon off;")
        );

        var containerId = adapter.startContainer(request);

        assertThat(containerId).isEqualTo("new-container-id");
        verify(dockerClient).startContainerCmd("new-container-id");
    }

    @Test
    void stopContainer_delegatesToDockerClient() {
        var stopCmd = mock(StopContainerCmd.class);
        when(dockerClient.stopContainerCmd("container1")).thenReturn(stopCmd);

        adapter.stopContainer("container1");

        verify(stopCmd).exec();
    }

    @Test
    void isContainerRunning_returnsTrueWhenRunning() {
        var inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("container1")).thenReturn(inspectCmd);

        var inspect = mock(InspectContainerResponse.class);
        when(inspectCmd.exec()).thenReturn(inspect);

        var state = mock(InspectContainerResponse.ContainerState.class);
        when(inspect.getState()).thenReturn(state);
        when(state.getRunning()).thenReturn(true);

        assertThat(adapter.isContainerRunning("container1")).isTrue();
    }

    @Test
    void getContainerExitCode_returnsExitCodeFromInspect() {
        var inspectCmd = mock(InspectContainerCmd.class);
        when(dockerClient.inspectContainerCmd("container1")).thenReturn(inspectCmd);

        var inspect = mock(InspectContainerResponse.class);
        when(inspectCmd.exec()).thenReturn(inspect);

        var state = mock(InspectContainerResponse.ContainerState.class);
        when(inspect.getState()).thenReturn(state);
        when(state.getExitCodeLong()).thenReturn(42L);

        assertThat(adapter.getContainerExitCode("container1")).isEqualTo(42L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void watchContainerEvents_forwardsStartEvent() throws InterruptedException {
        var eventsCmd = mockEventsCmd();
        when(dockerClient.eventsCmd()).thenReturn(eventsCmd);

        var callbackCaptor = ArgumentCaptor.forClass(ResultCallback.Adapter.class);
        when(eventsCmd.exec(callbackCaptor.capture())).thenReturn(null);

        var receivedEvent = new AtomicReference<ContainerEvent>();
        var latch = new CountDownLatch(1);

        adapter.watchContainerEvents(event -> {
            receivedEvent.set(event);
            latch.countDown();
        });

        // Simulate a start event
        var actor = new EventActor().withId("abc123").withAttributes(Map.of("name", "my-app"));
        var event = mock(Event.class);
        when(event.getAction()).thenReturn("start");
        when(event.getActor()).thenReturn(actor);
        when(event.getTime()).thenReturn(1700000000L);

        callbackCaptor.getValue().onNext(event);

        assertThat(latch.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(receivedEvent.get().containerId()).isEqualTo("abc123");
        assertThat(receivedEvent.get().containerName()).isEqualTo("my-app");
        assertThat(receivedEvent.get().eventType()).isEqualTo(ContainerEvent.EventType.STARTED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void watchContainerEvents_ignoresUnrelatedEvents() {
        var eventsCmd = mockEventsCmd();
        when(dockerClient.eventsCmd()).thenReturn(eventsCmd);

        var callbackCaptor = ArgumentCaptor.forClass(ResultCallback.Adapter.class);
        when(eventsCmd.exec(callbackCaptor.capture())).thenReturn(null);

        var receivedEvent = new AtomicReference<ContainerEvent>();
        adapter.watchContainerEvents(receivedEvent::set);

        var event = mock(Event.class);
        when(event.getAction()).thenReturn("attach");

        callbackCaptor.getValue().onNext(event);

        assertThat(receivedEvent.get()).isNull();
    }

    @Test
    void listContainers_wrapsDockerException() {
        var listCmd = mock(ListContainersCmd.class);
        when(dockerClient.listContainersCmd()).thenReturn(listCmd);
        when(listCmd.exec()).thenThrow(new DockerException("Connection refused", 500));

        assertThatThrownBy(() -> adapter.listContainers())
                .isInstanceOf(ContainerRuntimeException.class)
                .hasMessageContaining("Failed to list containers");
    }

    // -- Helper methods --

    @SuppressWarnings("unchecked")
    private void mockStatsCmd(String containerId, Statistics statistics) {
        var statsCmd = mock(StatsCmd.class);
        when(dockerClient.statsCmd(containerId)).thenReturn(statsCmd);
        when(statsCmd.withNoStream(true)).thenReturn(statsCmd);

        when(statsCmd.exec(any(ResultCallback.Adapter.class))).thenAnswer(invocation -> {
            ResultCallback.Adapter<Statistics> cb = invocation.getArgument(0);
            cb.onNext(statistics);
            return cb;
        });
    }

    private Statistics createStatistics(long totalCpu, long systemCpu, long onlineCpus,
                                        long memUsage, long memLimit,
                                        Map<String, StatisticNetworksConfig> networks,
                                        List<BlkioStatEntry> blkioEntries) {
        var cpuUsage = mock(CpuUsageConfig.class);
        when(cpuUsage.getTotalUsage()).thenReturn(totalCpu);

        var cpuStats = mock(CpuStatsConfig.class);
        lenient().when(cpuStats.getCpuUsage()).thenReturn(cpuUsage);
        lenient().when(cpuStats.getSystemCpuUsage()).thenReturn(systemCpu);
        lenient().when(cpuStats.getOnlineCpus()).thenReturn(onlineCpus);

        var memStats = mock(MemoryStatsConfig.class);
        lenient().when(memStats.getUsage()).thenReturn(memUsage);
        lenient().when(memStats.getLimit()).thenReturn(memLimit);

        var blkioStats = mock(BlkioStatsConfig.class);
        lenient().when(blkioStats.getIoServiceBytesRecursive()).thenReturn(blkioEntries);

        var stats = mock(Statistics.class);
        lenient().when(stats.getCpuStats()).thenReturn(cpuStats);
        lenient().when(stats.getMemoryStats()).thenReturn(memStats);
        lenient().when(stats.getNetworks()).thenReturn(networks);
        lenient().when(stats.getBlkioStats()).thenReturn(blkioStats);

        return stats;
    }

    private StatisticNetworksConfig networkConfig(long rxBytes, long txBytes) {
        var config = mock(StatisticNetworksConfig.class);
        when(config.getRxBytes()).thenReturn(rxBytes);
        when(config.getTxBytes()).thenReturn(txBytes);
        return config;
    }

    private EventsCmd mockEventsCmd() {
        var eventsCmd = mock(EventsCmd.class);
        when(dockerClient.eventsCmd()).thenReturn(eventsCmd);
        lenient().when(eventsCmd.withEventTypeFilter(any(String[].class))).thenReturn(eventsCmd);
        lenient().when(eventsCmd.withEventFilter(any(String[].class))).thenReturn(eventsCmd);
        return eventsCmd;
    }

    private BlkioStatEntry blkioEntry(String op, long value) {
        var entry = mock(BlkioStatEntry.class);
        when(entry.getOp()).thenReturn(op);
        when(entry.getValue()).thenReturn(value);
        return entry;
    }
}
