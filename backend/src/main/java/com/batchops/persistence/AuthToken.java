package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "auth_tokens")
public class AuthToken {
    @Id
    @Column(name = "token_hash")
    public String tokenHash;
    @Column(name = "user_id", nullable = false)
    public Long userId;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "expires_at", nullable = false)
    public Instant expiresAt;
}
