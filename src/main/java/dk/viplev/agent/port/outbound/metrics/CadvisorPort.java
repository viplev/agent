package dk.viplev.agent.port.outbound.metrics;

import dk.viplev.agent.domain.model.ContainerStats;

import java.util.Map;

public interface CadvisorPort {

    /**
     * Scrape container-level resource metrics from cadvisor.
     * Key = full Docker container ID (64-char hex).
     */
    Map<String, ContainerStats> scrapeAllContainerStats(String baseUrl);
}
