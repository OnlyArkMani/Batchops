package com.batchops.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentSourceRepository extends JpaRepository<DepartmentSource, Long> {
    Optional<DepartmentSource> findByNameIgnoreCase(String name);

    List<DepartmentSource> findByActiveTrueOrderByName();
}
