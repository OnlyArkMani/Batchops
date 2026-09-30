package com.batchops.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, String> {
    @Transactional
    @Modifying
    @Query("delete from AuthToken t where t.userId = :userId")
    int deleteAllForUser(Long userId);

    @Transactional
    @Modifying
    @Query("delete from AuthToken t where t.expiresAt < :now")
    int deleteExpired(Instant now);
}
