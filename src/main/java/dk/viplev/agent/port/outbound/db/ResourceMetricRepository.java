package dk.viplev.agent.port.outbound.db;

import dk.viplev.agent.domain.model.ResourceMetric;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

public interface ResourceMetricRepository extends JpaRepository<ResourceMetric, UUID> {

    List<ResourceMetric> findByFlushedFalseOrderByCollectedAtAsc();

    @Modifying
    @Transactional
    @Query("DELETE FROM ResourceMetric r WHERE r.machineId IS NULL")
    void deleteByMachineIdIsNull();
}
