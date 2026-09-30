package com.batchops.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KnownErrorRepository extends JpaRepository<KnownError, UUID> {
    List<KnownError> findByActiveTrueOrderByUpdatedAtDesc();

    boolean existsByName(String name);
}
