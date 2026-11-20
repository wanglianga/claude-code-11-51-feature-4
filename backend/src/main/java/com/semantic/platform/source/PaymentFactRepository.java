package com.semantic.platform.source;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface PaymentFactRepository extends JpaRepository<PaymentFact, Long> {
    List<PaymentFact> findByOrderIdIn(List<String> orderIds);
}
