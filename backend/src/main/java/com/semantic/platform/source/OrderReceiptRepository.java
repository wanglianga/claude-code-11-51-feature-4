package com.semantic.platform.source;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrderReceiptRepository extends JpaRepository<OrderReceipt, Long> {
    List<OrderReceipt> findByOrderIdIn(List<String> orderIds);
}
