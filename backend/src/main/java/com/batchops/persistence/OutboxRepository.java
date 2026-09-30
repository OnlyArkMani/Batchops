package com.batchops.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    /** SKIP LOCKED lets several relay instances drain the outbox without blocking or double-sending. */
    @Query(value = "select * from outbox where published_at is null order by id limit :batch for update skip locked",
            nativeQuery = true)
    List<OutboxEvent> lockNextBatch(int batch);

    @Modifying
    @Query("delete from OutboxEvent o where o.publishedAt is not null and o.publishedAt < :before")
    int deletePublishedBefore(java.time.Instant before);

    long countByPublishedAtIsNull();
}
