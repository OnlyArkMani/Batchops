package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dead_letters")
public class DeadLetter {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "event_id", nullable = false)
    public UUID eventId;
    @Column(name = "upload_id")
    public UUID uploadId;
    public String stage;
    public String topic;
    @Column(name = "msg_key")
    public String msgKey;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, Object> payload = new LinkedHashMap<>();
    @Column(name = "error_class")
    public String errorClass;
    @Column(columnDefinition = "text")
    public String error;
    public int attempts;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "replayed_at")
    public Instant replayedAt;
}
