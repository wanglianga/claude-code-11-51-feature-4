package com.semantic.platform.source;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface OrderFactRepository extends JpaRepository<OrderFact, Long> {
    List<OrderFact> findByBusinessDateBetween(LocalDate from, LocalDate to);
    List<OrderFact> findByStoreIdAndBusinessDateBetween(String storeId, LocalDate from, LocalDate to);
    List<OrderFact> findByOrderIdIn(List<String> orderIds);
}
