package com.batchops.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface UploadRepository extends JpaRepository<Upload, UUID>, JpaSpecificationExecutor<Upload> {
    Optional<Upload> findFirstByDepartmentAndSha256AndStatusNot(String department, String sha256, String status);

    long countByReceivedAtGreaterThanEqualAndReceivedAtLessThan(java.time.Instant from, java.time.Instant to);

    List<Upload> findByStatusAndReceivedAtBefore(String status, java.time.Instant before);
}
