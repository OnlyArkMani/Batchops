package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox")
public class OutboxEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "event_id", nullable = false, unique = true)
    public UUID eventId = UUID.randomUUID();
    public String topic;
    @Column(name = "msg_key")
    public String msgKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, Object> payload = new LinkedHashMap<>();
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, String> headers = new LinkedHashMap<>();
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "published_at")
    public Instant publishedAt;
}
