package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "tickets")
public class Ticket {
    @Id
    @Column(name = "ticket_id")
    public UUID ticketId = UUID.randomUUID();
    @ManyToOne
    @JoinColumn(name = "incident_id")
    public Incident incident;
    public String source = "system";
    public String status = "open";
    public String assignee;
    @Column(name = "created_by")
    public String createdBy;
    @Column(name = "resolved_by")
    public String resolvedBy;
    @Column(name = "resolution_type")
    public String resolutionType;
    @Column(name = "resolution_notes", columnDefinition = "text")
    public String resolutionNotes;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public List<Map<String, Object>> timeline = new ArrayList<>();
    public String title;
    @Column(columnDefinition = "text")
    public String description;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
    @Column(name = "resolved_at")
    public Instant resolvedAt;
}
