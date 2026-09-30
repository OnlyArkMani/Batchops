package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "department_sources")
public class DepartmentSource {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    public String name;
    public String code;
    public String description = "";
    @Column(name = "schedule_hint")
    public String scheduleHint = "";
    public boolean active = true;
    @Column(name = "last_ingested_at")
    public Instant lastIngestedAt;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
}
