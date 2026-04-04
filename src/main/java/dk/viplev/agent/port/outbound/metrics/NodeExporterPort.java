package dk.viplev.agent.port.outbound.metrics;

import dk.viplev.agent.domain.model.HostStats;

public interface NodeExporterPort {

    /**
     * Scrape host resource metrics (CPU, memory, network, disk) from node_exporter.
     *
     * @param baseUrl base URL of the node_exporter instance, e.g. {@code http://viplev-node-exporter:9100}
     */
    HostStats scrapeHostStats(String baseUrl);
}
