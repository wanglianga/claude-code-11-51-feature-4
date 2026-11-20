package com.semantic.platform.distribution;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface DistCompileRepository extends JpaRepository<DistCompile, Long> {
    DistCompile findTopByOrderByIdDesc();
}
