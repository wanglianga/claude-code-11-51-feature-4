package com.semantic.platform.run;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RunCellLineageRepository extends JpaRepository<RunCellLineage, Long> {
    List<RunCellLineage> findByRunIdAndCellKey(Long runId, String cellKey);
    void deleteByRunId(Long runId);
}
