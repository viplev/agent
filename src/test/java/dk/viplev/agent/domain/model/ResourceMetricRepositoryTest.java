package dk.viplev.agent.domain.model;

import dk.viplev.agent.port.outbound.db.ResourceMetricRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DataJpaTest
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ResourceMetricRepositoryTest {

    @Autowired
    private ResourceMetricRepository repository;

    @Test
    void shouldPersistAndRetrieveResourceMetric() {
        var metric = ResourceMetric.builder()
                .collectedAt(LocalDateTime.now())
                .targetType(TargetType.HOST)
                .targetName("machine-1")
                .cpuPercentage(45.2)
                .memoryUsageBytes(1024.0)
                .memoryLimitBytes(2048.0)
                .build();

        var saved = repository.save(metric);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.isFlushed()).isFalse();

        var found = repository.findById(saved.getId());
        assertThat(found).isPresent();
        assertThat(found.get().getTargetName()).isEqualTo("machine-1");
        assertThat(found.get().getCpuPercentage()).isCloseTo(45.2, within(0.01));
    }

    @Test
    void shouldFindUnflushedMetricsOrderedByCollectedAt() {
        var now = LocalDateTime.now();

        var metric1 = ResourceMetric.builder()
                .collectedAt(now.minusSeconds(2))
                .targetType(TargetType.HOST)
                .targetName("machine-1")
                .cpuPercentage(10.0)
                .build();

        var metric2 = ResourceMetric.builder()
                .collectedAt(now.minusSeconds(1))
                .targetType(TargetType.SERVICE)
                .targetName("api-service")
                .cpuPercentage(20.0)
                .build();

        var metric3 = ResourceMetric.builder()
                .collectedAt(now)
                .targetType(TargetType.HOST)
                .targetName("machine-1")
                .cpuPercentage(30.0)
                .flushed(true)
                .build();

        repository.saveAll(List.of(metric1, metric2, metric3));

        List<ResourceMetric> unflushed = repository.findByFlushedFalseOrderByCollectedAtAsc();

        assertThat(unflushed).hasSize(2);
        assertThat(unflushed.get(0).getCpuPercentage()).isCloseTo(10.0, within(0.01));
        assertThat(unflushed.get(1).getCpuPercentage()).isCloseTo(20.0, within(0.01));
    }
}
