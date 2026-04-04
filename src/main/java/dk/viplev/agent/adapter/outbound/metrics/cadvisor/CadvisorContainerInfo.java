package dk.viplev.agent.adapter.outbound.metrics.cadvisor;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
record CadvisorContainerInfo(
        @JsonProperty("spec") CadvisorSpec spec,
        @JsonProperty("stats") List<CadvisorStat> stats
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorSpec(
            @JsonProperty("memory") CadvisorMemorySpec memory
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorMemorySpec(
            @JsonProperty("limit") long limit
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorStat(
            @JsonProperty("timestamp") Instant timestamp,
            @JsonProperty("cpu") CadvisorCpuStats cpu,
            @JsonProperty("memory") CadvisorMemoryStats memory,
            @JsonProperty("network") CadvisorNetworkStats network,
            @JsonProperty("diskio") CadvisorDiskIoStats diskio
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorCpuStats(
            @JsonProperty("usage") CadvisorCpuUsage usage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorCpuUsage(
            @JsonProperty("total") long total
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorMemoryStats(
            @JsonProperty("usage") long usage
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorNetworkStats(
            @JsonProperty("interfaces") List<CadvisorInterface> interfaces
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorInterface(
            @JsonProperty("name") String name,
            @JsonProperty("rx_bytes") long rxBytes,
            @JsonProperty("tx_bytes") long txBytes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorDiskIoStats(
            @JsonProperty("io_service_bytes") List<CadvisorIoServiceEntry> ioServiceBytes
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CadvisorIoServiceEntry(
            @JsonProperty("stats") Map<String, Long> stats
    ) {}
}
