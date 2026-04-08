package dk.viplev.agent.domain.mapper;

import dk.viplev.agent.domain.model.ResourceMetric;
import dk.viplev.agent.generated.model.MetricDataPointDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

@Mapper(componentModel = "spring")
public interface ResourceMetricMapper {

    @Mapping(target = "collectedAt", source = "collectedAt")
    MetricDataPointDTO toDataPoint(ResourceMetric metric);

    List<MetricDataPointDTO> toDataPoints(List<ResourceMetric> metrics);

    default OffsetDateTime mapLocalDateTime(LocalDateTime ldt) {
        if (ldt == null) {
            return null;
        }
        return ldt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
