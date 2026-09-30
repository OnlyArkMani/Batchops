package com.batchops.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface JobRunRepository extends JpaRepository<JobRun, Long>, JpaSpecificationExecutor<JobRun> {
    Optional<JobRun> findByRunId(UUID runId);

    List<JobRun> findByStatusAndStartedAtBefore(String status, Instant before);

    @Query("select count(r) from JobRun r where (r.startedAt >= :from and r.startedAt < :to) or (r.finishedAt >= :from and r.finishedAt < :to)")
    long countActiveBetween(Instant from, Instant to);

    Optional<JobRun> findFirstByUploadUploadIdAndJobNameAndFinishedAtIsNullOrderByIdDesc(UUID uploadId, String jobName);

    long countByStatus(String status);
}
