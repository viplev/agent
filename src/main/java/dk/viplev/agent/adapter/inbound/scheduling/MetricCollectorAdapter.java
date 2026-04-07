package dk.viplev.agent.adapter.inbound.scheduling;

import dk.viplev.agent.generated.model.BenchmarkRunStatusUpdateDTO;
import dk.viplev.agent.generated.model.MessageDTO;
import dk.viplev.agent.port.inbound.MetricCollectionUseCase;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@Profile("docker")
public class MetricCollectorAdapter {

    private static final Logger log = LoggerFactory.getLogger(MetricCollectorAdapter.class);
    private static final long POLL_INTERVAL_MS = 5000;

    private final MetricCollectionUseCase metricCollectionUseCase;
    private final ViplevApiPort viplevApiPort;
    private final ScheduledExecutorService poller;

    public MetricCollectorAdapter(MetricCollectionUseCase metricCollectionUseCase,
                                  ViplevApiPort viplevApiPort) {
        this.metricCollectionUseCase = metricCollectionUseCase;
        this.viplevApiPort = viplevApiPort;
        this.poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "message-poller");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Starting message polling loop (interval={}ms)", POLL_INTERVAL_MS);
        poller.scheduleAtFixedRate(this::pollMessagesSafely, 0, POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        poller.shutdownNow();
    }

    public void startCollection(UUID benchmarkId, UUID runId) {
        metricCollectionUseCase.startCollection(benchmarkId, runId);
    }

    public void stopCollection() {
        metricCollectionUseCase.stopCollection();
    }

    void pollMessagesSafely() {
        try {
            List<MessageDTO> messages = viplevApiPort.pollMessages();
            for (MessageDTO message : messages) {
                handleMessage(message);
            }
        } catch (Exception e) {
            log.warn("Failed to poll messages from VIPLEV; will retry on next poll", e);
        }
    }

    private void handleMessage(MessageDTO message) {
        if (message.getMessageType() == null) {
            log.warn("Received message with null type; ignoring");
            return;
        }

        UUID benchmarkId = message.getBenchmarkId();
        UUID runId = message.getRunId();

        switch (message.getMessageType()) {
            case PENDING_START -> {
                log.info("Received PENDING_START for benchmark={} run={}", benchmarkId, runId);
                metricCollectionUseCase.startCollection(benchmarkId, runId);
                viplevApiPort.updateRunStatus(benchmarkId, runId,
                        new BenchmarkRunStatusUpdateDTO().status(BenchmarkRunStatusUpdateDTO.StatusEnum.STARTED));
            }
            case PENDING_STOP -> {
                log.info("Received PENDING_STOP for benchmark={} run={}", benchmarkId, runId);
                metricCollectionUseCase.stopCollection();
                viplevApiPort.updateRunStatus(benchmarkId, runId,
                        new BenchmarkRunStatusUpdateDTO().status(BenchmarkRunStatusUpdateDTO.StatusEnum.STOPPED));
            }
            default -> log.warn("Unknown message type: {}", message.getMessageType());
        }
    }
}
