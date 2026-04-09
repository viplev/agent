package dk.viplev.agent.adapter.inbound.scheduling;

import dk.viplev.agent.domain.model.RunContext;
import dk.viplev.agent.generated.model.BenchmarkDTO;
import dk.viplev.agent.generated.model.MessageDTO;
import dk.viplev.agent.port.inbound.BenchmarkExecutionUseCase;
import dk.viplev.agent.port.outbound.rest.ViplevApiPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
@Profile("docker")
public class MessagePollingAdapter {

    private static final Logger log = LoggerFactory.getLogger(MessagePollingAdapter.class);

    private final ViplevApiPort viplevApiPort;
    private final BenchmarkExecutionUseCase benchmarkExecutionUseCase;
    private final RunContext runContext;
    private final long idlePollIntervalMs;
    private final long activePollIntervalMs;

    private volatile long lastPollAtMs;

    public MessagePollingAdapter(ViplevApiPort viplevApiPort,
                                 BenchmarkExecutionUseCase benchmarkExecutionUseCase,
                                 RunContext runContext,
                                 @Value("${agent.message-polling-idle-interval-ms:15000}") long idlePollIntervalMs,
                                 @Value("${agent.message-polling-active-interval-ms:5000}") long activePollIntervalMs) {
        this.viplevApiPort = viplevApiPort;
        this.benchmarkExecutionUseCase = benchmarkExecutionUseCase;
        this.runContext = runContext;
        this.idlePollIntervalMs = idlePollIntervalMs;
        this.activePollIntervalMs = activePollIntervalMs;
    }

    @Scheduled(fixedDelayString = "${agent.message-polling-tick-ms:1000}")
    public void pollMessagesSafely() {
        pollMessagesSafely(System.currentTimeMillis());
    }

    void pollMessagesSafely(long nowMs) {
        if (!shouldPollNow(nowMs)) {
            return;
        }

        try {
            List<MessageDTO> messages = viplevApiPort.pollMessages();
            lastPollAtMs = nowMs;
            handleMessages(messages);
        } catch (Exception e) {
            log.warn("Failed to poll messages from VIPLEV; will retry on next poll", e);
        }
    }

    boolean shouldPollNow(long nowMs) {
        if (lastPollAtMs == 0) {
            return true;
        }
        long intervalMs = runContext.isActive() ? activePollIntervalMs : idlePollIntervalMs;
        return nowMs - lastPollAtMs >= intervalMs;
    }

    private void handleMessages(List<MessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }

        if (messages.size() > 1) {
            log.warn("Received {} pending messages; processing only the first one", messages.size());
        }

        MessageDTO message = messages.getFirst();
        if (message == null) {
            log.warn("Received null message; ignoring");
            return;
        }
        handleMessage(message);
    }

    private void handleMessage(MessageDTO message) {
        if (message.getMessageType() == null) {
            log.warn("Received message with null type; ignoring");
            return;
        }

        UUID benchmarkId = message.getBenchmarkId();
        UUID runId = message.getRunId();
        if (benchmarkId == null || runId == null) {
            log.warn("Received message of type {} with null benchmarkId or runId; ignoring",
                    message.getMessageType());
            return;
        }

        switch (message.getMessageType()) {
            case PENDING_START -> {
                log.info("Received PENDING_START for benchmark={} run={}", benchmarkId, runId);
                BenchmarkDTO benchmark = viplevApiPort.getBenchmark(benchmarkId);
                String k6Instructions = benchmark != null ? benchmark.getK6Instructions() : null;
                benchmarkExecutionUseCase.startRun(benchmarkId, runId, k6Instructions);
            }
            case PENDING_STOP -> {
                log.info("Received PENDING_STOP for benchmark={} run={}", benchmarkId, runId);
                benchmarkExecutionUseCase.stopRun(benchmarkId, runId);
            }
            default -> log.warn("Unknown message type: {}", message.getMessageType());
        }
    }
}
