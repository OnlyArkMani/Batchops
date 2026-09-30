package com.batchops.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "job_runs")
public class JobRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @ManyToOne(optional = false)
    @JoinColumn(name = "job_id")
    public Job job;
    @ManyToOne
    @JoinColumn(name = "upload_id")
    public Upload upload;
    @Column(name = "run_id", unique = true)
    public UUID runId = UUID.randomUUID();
    public String status = "queued";
    @Column(name = "started_at")
    public Instant startedAt;
    @Column(name = "finished_at")
    public Instant finishedAt;
    @Column(name = "duration_ms")
    public Integer durationMs;
    @Column(name = "exit_code")
    public Integer exitCode;
    @Column(columnDefinition = "text")
    public String logs = "";
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public Map<String, Object> details = new LinkedHashMap<>();
    @Column(name = "retry_count")
    public int retryCount;
    @Column(name = "max_retries")
    public int maxRetries = 3;
}
