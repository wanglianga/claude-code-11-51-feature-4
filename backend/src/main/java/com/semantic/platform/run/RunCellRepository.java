package com.semantic.platform.run;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface RunCellRepository extends JpaRepository<RunCell, Long> {
    List<RunCell> findByRunId(Long runId);
    void deleteByRunId(Long runId);
}
