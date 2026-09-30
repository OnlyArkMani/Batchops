package com.batchops.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface DepartmentRecordRepository extends JpaRepository<DepartmentRecord, Long> {
    List<DepartmentRecord> findBySourceIdOrderByRecordedAtDesc(Long sourceId);

    @Transactional
    @Modifying
    @Query("delete from DepartmentRecord r where r.recordedAt < :before")
    int deleteOlderThan(Instant before);
}
