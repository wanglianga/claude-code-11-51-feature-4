package com.semantic.platform.distribution;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DistSummaryRepository extends JpaRepository<DistSummary, Long> {
    List<DistSummary> findByStoreIdAndBizWeek(String storeId, String bizWeek);
}
