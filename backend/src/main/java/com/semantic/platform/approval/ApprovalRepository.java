package com.semantic.platform.approval;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface ApprovalRepository extends JpaRepository<Approval, Long> {
    List<Approval> findByStatus(String status);
    List<Approval> findByMetricKeyOrderBySubmittedAtDesc(String metricKey);
}
