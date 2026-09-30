package com.batchops.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRepository extends JpaRepository<Job, Long> {
    Optional<Job> findByName(String name);

    java.util.List<Job> findByScheduleCronIsNotNull();
}
