package dk.viplev.agent.adapter.inbound.scheduling;

import dk.viplev.agent.domain.model.ContainerEvent;
import dk.viplev.agent.port.inbound.ServiceDiscoveryUseCase;
import dk.viplev.agent.port.outbound.container.ContainerPort;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class ServiceWatcherAdapter {

    private static final Logger log = LoggerFactory.getLogger(ServiceWatcherAdapter.class);
    private static final long DEFAULT_DEBOUNCE_DELAY_MS = 2000;

    private final ServiceDiscoveryUseCase serviceDiscoveryUseCase;
    private final ContainerPort containerPort;
    private final long debounceDelayMs;
    private final ScheduledExecutorService scheduler;
    private volatile ScheduledFuture<?> pendingSync;

    @Autowired
    public ServiceWatcherAdapter(ServiceDiscoveryUseCase serviceDiscoveryUseCase,
                                 ContainerPort containerPort) {
        this(serviceDiscoveryUseCase, containerPort, DEFAULT_DEBOUNCE_DELAY_MS);
    }

    ServiceWatcherAdapter(ServiceDiscoveryUseCase serviceDiscoveryUseCase,
                          ContainerPort containerPort,
                          long debounceDelayMs) {
        this.serviceDiscoveryUseCase = serviceDiscoveryUseCase;
        this.containerPort = containerPort;
        this.debounceDelayMs = debounceDelayMs;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "service-watcher-debounce");
            t.setDaemon(true);
            return t;
        });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("Application ready, starting container event watcher and performing initial service sync");
        containerPort.watchContainerEvents(this::onContainerEvent);
        log.info("Watching for container events");
        scheduler.submit(this::executeSyncSafely);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }

    private void onContainerEvent(ContainerEvent event) {
        log.info("Container event: {} {} ({})",
                event.eventType(), event.containerName(), event.containerId());
        scheduleSync();
    }

    private synchronized void scheduleSync() {
        if (pendingSync != null && !pendingSync.isDone()) {
            pendingSync.cancel(false);
        }
        pendingSync = scheduler.schedule(
                this::executeSyncSafely, debounceDelayMs, TimeUnit.MILLISECONDS);
    }

    private void executeSyncSafely() {
        try {
            log.info("Syncing services with VIPLEV");
            serviceDiscoveryUseCase.syncServices();
            log.info("Service sync completed successfully");
        } catch (Exception e) {
            log.error("Failed to sync services", e);
        }
    }
}
