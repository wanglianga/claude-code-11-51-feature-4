package com.semantic.platform.metric;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MetricDefRepository extends JpaRepository<MetricDef, Long> {
    Optional<MetricDef> findByMetricKey(String metricKey);
}
