package com.semantic.platform.metric;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface MetricEdgeRepository extends JpaRepository<MetricEdge, Long> {
    List<MetricEdge> findByFromMetric(String fromMetric);
    List<MetricEdge> findByToMetric(String toMetric);
    void deleteByFromMetricAndFromVersion(String fromMetric, Integer fromVersion);
}
