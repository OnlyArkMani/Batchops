package com.batchops.persistence;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "department_records")
public class DepartmentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(name = "source_id", nullable = false)
    public Long sourceId;
    @Column(name = "student_id")
    public String studentId;
    @Column(name = "student_name")
    public String studentName;
    @Column(name = "class_name")
    public String className = "";
    public BigDecimal score;
    @Column(name = "attendance_percent")
    public BigDecimal attendancePercent;
    public String status = "";
    @Column(name = "recorded_at")
    public Instant recordedAt = Instant.now();
    public String notes;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
}
