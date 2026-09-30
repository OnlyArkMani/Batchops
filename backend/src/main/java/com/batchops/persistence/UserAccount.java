package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, unique = true)
    public String username;
    public String email = "";
    @Column(name = "password_hash", nullable = false)
    public String passwordHash;
    public String role = "user";
    @Column(name = "is_superuser")
    public boolean superuser;
    @Column(name = "email_verified")
    public boolean emailVerified;
    public boolean active = true;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();

    public boolean isAdmin() {
        return superuser || "admin".equals(role);
    }

    public boolean isModerator() {
        return superuser || "admin".equals(role) || "moderator".equals(role);
    }

    public String effectiveRole() {
        return superuser ? "admin" : role;
    }
}
