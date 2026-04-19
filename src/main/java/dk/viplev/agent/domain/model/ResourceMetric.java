package dk.viplev.agent.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "resource_metrics")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 10)
    private TargetType targetType;

    @Column(name = "target_name", nullable = false)
    private String targetName;

    @Column(name = "machine_id", nullable = false)
    private String machineId;

    @Column(name = "container_id")
    private String containerId;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "cpu_percentage")
    private Double cpuPercentage;

    @Column(name = "memory_usage_bytes")
    private Double memoryUsageBytes;

    @Column(name = "memory_limit_bytes")
    private Double memoryLimitBytes;

    @Column(name = "network_in_bytes")
    private Double networkInBytes;

    @Column(name = "network_out_bytes")
    private Double networkOutBytes;

    @Column(name = "block_in_bytes")
    private Double blockInBytes;

    @Column(name = "block_out_bytes")
    private Double blockOutBytes;

    @Builder.Default
    @Column(name = "flushed", nullable = false)
    private boolean flushed = false;
}
