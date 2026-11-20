package com.semantic.platform.metric;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface MetricVersionRepository extends JpaRepository<MetricVersion, Long> {
    List<MetricVersion> findByMetricKeyOrderByVersionDesc(String metricKey);
    Optional<MetricVersion> findByMetricKeyAndVersion(String metricKey, Integer version);
    Optional<MetricVersion> findFirstByMetricKeyAndStatusOrderByVersionDesc(String metricKey, String status);
    List<MetricVersion> findByStatus(String status);
}
