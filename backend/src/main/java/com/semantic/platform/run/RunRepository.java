package com.semantic.platform.run;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RunRepository extends JpaRepository<Run, Long> {
    List<Run> findByMetricKeyOrderByStartedAtDesc(String metricKey);
    List<Run> findByMetricKeyAndStatus(String metricKey, String status);
}
