package dk.viplev.agent.port.outbound.db;

import dk.viplev.agent.domain.model.ResourceMetric;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ResourceMetricRepository extends JpaRepository<ResourceMetric, UUID> {

    List<ResourceMetric> findByFlushedFalseOrderByCollectedAtAsc();
}
