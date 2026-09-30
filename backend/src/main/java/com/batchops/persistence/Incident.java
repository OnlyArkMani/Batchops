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
@Table(name = "incidents")
public class Incident {
    @Id
    @Column(name = "incident_id")
    public UUID incidentId = UUID.randomUUID();
    @ManyToOne(optional = false)
    @JoinColumn(name = "upload_id")
    public Upload upload;
    @ManyToOne
    @JoinColumn(name = "job_run_id")
    public JobRun jobRun;
    @ManyToOne
    @JoinColumn(name = "matched_known_error")
    public KnownError matchedKnownError;
    public String fingerprint;
    @Column(name = "occurrence_count")
    public int occurrenceCount = 1;
    @Column(columnDefinition = "text")
    public String error;
    @Column(name = "root_cause", columnDefinition = "text")
    public String rootCause;
    @Column(name = "corrective_action", columnDefinition = "text")
    public String correctiveAction;
    @Column(name = "impact_summary", columnDefinition = "text")
    public String impactSummary;
    @Column(name = "analysis_notes", columnDefinition = "text")
    public String analysisNotes;
    @Column(name = "resolution_report", columnDefinition = "text")
    public String resolutionReport;
    public String severity = "medium";
    public String category;
    @Column(name = "detection_source")
    public String detectionSource;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    public List<Map<String, Object>> timeline = new ArrayList<>();
    @Column(name = "auto_retry_count")
    public int autoRetryCount;
    @Column(name = "max_auto_retries")
    public int maxAutoRetries = 2;
    @Column(name = "manual_retries")
    public int manualRetries;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pending_fixes", columnDefinition = "jsonb")
    public List<String> pendingFixes = new ArrayList<>();
    @Column(name = "archived_at")
    public Instant archivedAt;
    @Column(name = "resolved_by")
    public String resolvedBy;
    @Column(name = "resolved_at")
    public Instant resolvedAt;
    public String state = "open";
    public String assignee;
    @Column(name = "created_at")
    public Instant createdAt = Instant.now();
    @Column(name = "updated_at")
    public Instant updatedAt = Instant.now();
}
