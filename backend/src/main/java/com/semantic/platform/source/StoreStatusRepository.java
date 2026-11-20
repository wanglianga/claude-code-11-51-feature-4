package com.semantic.platform.source;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface StoreStatusRepository extends JpaRepository<StoreStatus, Long> {
    List<StoreStatus> findByBusinessDateBetween(LocalDate from, LocalDate to);
    List<StoreStatus> findByStoreIdAndBusinessDateBetween(String storeId, LocalDate from, LocalDate to);
}
