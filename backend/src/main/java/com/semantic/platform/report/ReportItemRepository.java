package com.semantic.platform.report;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ReportItemRepository extends JpaRepository<ReportItem, Long> {
    List<ReportItem> findByReportId(Long reportId);
    List<ReportItem> findByMetricKey(String metricKey);
    void deleteByReportId(Long reportId);
}
