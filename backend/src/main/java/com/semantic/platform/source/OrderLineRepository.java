package com.semantic.platform.source;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface OrderLineRepository extends JpaRepository<OrderLine, Long> {
    List<OrderLine> findByBusinessDateBetween(LocalDate from, LocalDate to);
    List<OrderLine> findByOrderIdIn(List<String> orderIds);
}
