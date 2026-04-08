package dk.viplev.agent.domain.mapper;

import dk.viplev.agent.domain.model.ResourceMetric;
import dk.viplev.agent.domain.model.TargetType;
import dk.viplev.agent.generated.model.MetricDataPointDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ResourceMetricMapperTest {

    private ResourceMetricMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = Mappers.getMapper(ResourceMetricMapper.class);
    }

    @Test
    void toDataPoint_mapsAllFields() {
        var collectedAt = LocalDateTime.of(2026, 4, 6, 12, 0, 0);
        var metric = ResourceMetric.builder()
                .collectedAt(collectedAt)
                .targetType(TargetType.HOST)
                .targetName("machine-1")
                .machineId("machine-1")
                .cpuPercentage(45.5)
                .memoryUsageBytes(1024.0)
                .memoryLimitBytes(2048.0)
                .networkInBytes(500.0)
                .networkOutBytes(300.0)
                .blockInBytes(100.0)
                .blockOutBytes(50.0)
                .build();

        MetricDataPointDTO dto = mapper.toDataPoint(metric);

        OffsetDateTime expectedTime = collectedAt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        assertThat(dto.getCollectedAt()).isEqualTo(expectedTime);
        assertThat(dto.getCpuPercentage()).isCloseTo(45.5, within(0.01));
        assertThat(dto.getMemoryUsageBytes()).isCloseTo(1024.0, within(0.01));
        assertThat(dto.getMemoryLimitBytes()).isCloseTo(2048.0, within(0.01));
        assertThat(dto.getNetworkInBytes()).isCloseTo(500.0, within(0.01));
        assertThat(dto.getNetworkOutBytes()).isCloseTo(300.0, within(0.01));
        assertThat(dto.getBlockInBytes()).isCloseTo(100.0, within(0.01));
        assertThat(dto.getBlockOutBytes()).isCloseTo(50.0, within(0.01));
    }

    @Test
    void toDataPoints_mapsList() {
        var metric1 = ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.HOST)
                .targetName("machine-1")
                .machineId("machine-1")
                .cpuPercentage(10.0)
                .build();
        var metric2 = ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.SERVICE)
                .targetName("nginx")
                .machineId("machine-1")
                .cpuPercentage(20.0)
                .build();
        var metric3 = ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.SERVICE)
                .targetName("redis")
                .machineId("machine-1")
                .cpuPercentage(30.0)
                .build();

        List<MetricDataPointDTO> dtos = mapper.toDataPoints(List.of(metric1, metric2, metric3));

        assertThat(dtos).hasSize(3);
        assertThat(dtos.get(0).getCpuPercentage()).isCloseTo(10.0, within(0.01));
        assertThat(dtos.get(1).getCpuPercentage()).isCloseTo(20.0, within(0.01));
        assertThat(dtos.get(2).getCpuPercentage()).isCloseTo(30.0, within(0.01));
    }

    @Test
    void toDataPoint_handlesNullOptionalFields() {
        var metric = ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.HOST)
                .targetName("machine-1")
                .machineId("machine-1")
                .build();

        MetricDataPointDTO dto = mapper.toDataPoint(metric);

        assertThat(dto.getCollectedAt()).isNotNull();
        assertThat(dto.getCpuPercentage()).isNull();
        assertThat(dto.getMemoryUsageBytes()).isNull();
        assertThat(dto.getMemoryLimitBytes()).isNull();
        assertThat(dto.getNetworkInBytes()).isNull();
        assertThat(dto.getNetworkOutBytes()).isNull();
        assertThat(dto.getBlockInBytes()).isNull();
        assertThat(dto.getBlockOutBytes()).isNull();
    }
}
