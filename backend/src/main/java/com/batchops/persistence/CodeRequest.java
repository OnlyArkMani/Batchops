package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "code_requests")
public class CodeRequest {
    @Id
    @Column(name = "request_id")
    public UUID requestId = UUID.randomUUID();
    @Column(name = "user_id", nullable = false)
    public Long userId;
    @Column(nullable = false)
    public String purpose;
    @Column(name = "code_hash", nullable = false)
    public String codeHash;
    public int attempts;
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
    @Column(name = "used_at")
    public Instant usedAt;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
}
