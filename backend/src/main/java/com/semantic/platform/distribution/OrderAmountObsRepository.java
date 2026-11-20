package com.semantic.platform.distribution;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface OrderAmountObsRepository extends JpaRepository<OrderAmountObs, Long> {
    List<OrderAmountObs> findByOrderKeyIn(List<String> orderKeys);
    List<OrderAmountObs> findByStoreIdAndBizWeek(String storeId, String bizWeek);
}
