package dk.viplev.agent.domain.mapper;

import dk.viplev.agent.domain.model.ResourceMetric;
import dk.viplev.agent.generated.model.MetricDataPointDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.util.List;

@Mapper(componentModel = "spring")
public interface ResourceMetricMapper {

    @Mapping(target = "collectedAt", source = "collectedAt")
    MetricDataPointDTO toDataPoint(ResourceMetric metric);

    List<MetricDataPointDTO> toDataPoints(List<ResourceMetric> metrics);
}
