package com.semantic.platform.source;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface RefundFactRepository extends JpaRepository<RefundFact, Long> {
    List<RefundFact> findByBusinessDateBetween(LocalDate from, LocalDate to);
    List<RefundFact> findByStoreIdAndBusinessDateBetween(String storeId, LocalDate from, LocalDate to);
    List<RefundFact> findByOrderIdIn(List<String> orderIds);
}
