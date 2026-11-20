package com.semantic.platform.distribution;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DedupDecisionRepository extends JpaRepository<DedupDecision, Long> {
    DedupDecision findByDuplicateKey(String duplicateKey);
}
