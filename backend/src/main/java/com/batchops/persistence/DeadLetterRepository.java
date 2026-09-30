package com.batchops.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
    List<DeadLetter> findByReplayedAtIsNullOrderByCreatedAtAsc();

    long countByReplayedAtIsNull();
}
