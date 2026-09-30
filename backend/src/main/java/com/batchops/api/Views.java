package com.batchops.api;

import com.batchops.persistence.Incident;
import com.batchops.persistence.Job;
import com.batchops.persistence.JobRun;
import com.batchops.persistence.KnownError;
import com.batchops.persistence.Ticket;
import com.batchops.persistence.Upload;
import com.batchops.persistence.UserAccount;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON shapes identical to the DRF serializers (snake_case keys, same field names) so the React app
 * works unchanged. A few additive fields expose new capabilities; clients that ignore them are unaffected.
 */
public final class Views {

    private Views() {
    }

    public static Map<String, Object> upload(Upload u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("upload_id", u.uploadId);
        m.put("department", u.department);
        m.put("filename", u.filename);
        m.put("mime_type", u.mimeType);
        m.put("status", u.status);
        m.put("received_at", u.receivedAt);
        m.put("notes", u.notes);
        m.put("file_path", u.filePath);
        m.put("report_path", u.reportPath);
        m.put("report_generated_at", u.reportGeneratedAt);
        // Bodies are served by /api/reports/summary (object storage), not inlined in list payloads.
        m.put("report_csv", "");
        m.put("report_meta", u.reportMeta);
        m.put("report_pdf", "");
        m.put("process_mode", u.processMode);
        m.put("process_config", u.processConfig);
        m.put("version", u.version);
        m.put("size_bytes", u.sizeBytes);
        m.put("validation_report", u.validationReport);
        return m;
    }

    public static Map<String, Object> job(Job j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", j.id);
        m.put("name", j.name);
        m.put("job_type", j.jobType);
        m.put("config", j.config);
        m.put("schedule_cron", j.scheduleCron);
        m.put("created_at", j.createdAt);
        m.put("updated_at", j.updatedAt);
        return m;
    }

    public static Map<String, Object> jobRun(JobRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("job", r.job.id);
        m.put("upload", r.upload == null ? null : r.upload.uploadId);
        m.put("run_id", r.runId);
        m.put("status", r.status);
        m.put("started_at", r.startedAt);
        m.put("finished_at", r.finishedAt);
        m.put("duration_ms", r.durationMs);
        m.put("exit_code", r.exitCode);
        m.put("logs", r.logs);
        m.put("details", r.details);
        m.put("retry_count", r.retryCount);
        m.put("max_retries", r.maxRetries);
        m.put("job_name", r.job.name);
        m.put("upload_filename", r.upload == null ? null : r.upload.filename);
        return m;
    }

    public static Map<String, Object> knownError(KnownError k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error_id", k.errorId);
        m.put("name", k.name);
        m.put("pattern", k.pattern);
        m.put("fix", k.fix);
        m.put("examples", k.examples);
        m.put("active", k.active);
        m.put("created_at", k.createdAt);
        m.put("updated_at", k.updatedAt);
        return m;
    }

    public static Map<String, Object> incident(Incident i) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("incident_id", i.incidentId);
        m.put("upload", i.upload.uploadId);
        m.put("job_run", i.jobRun == null ? null : i.jobRun.id);
        m.put("matched_known_error", i.matchedKnownError == null ? null : i.matchedKnownError.errorId);
        m.put("error", i.error);
        m.put("root_cause", i.rootCause);
        m.put("corrective_action", i.correctiveAction);
        m.put("impact_summary", i.impactSummary);
        m.put("analysis_notes", i.analysisNotes);
        m.put("resolution_report", i.resolutionReport);
        m.put("severity", i.severity);
        m.put("category", i.category);
        m.put("detection_source", i.detectionSource);
        m.put("timeline", i.timeline);
        m.put("auto_retry_count", i.autoRetryCount);
        m.put("max_auto_retries", i.maxAutoRetries);
        m.put("archived_at", i.archivedAt);
        m.put("resolved_by", i.resolvedBy);
        m.put("resolved_at", i.resolvedAt);
        m.put("state", i.state);
        m.put("assignee", i.assignee);
        m.put("created_at", i.createdAt);
        m.put("updated_at", i.updatedAt);
        m.put("upload_filename", i.upload.filename);
        m.put("job_name", i.jobRun == null ? null : i.jobRun.job.name);
        m.put("matched_known_error_name", i.matchedKnownError == null ? null : i.matchedKnownError.name);
        m.put("is_known", i.matchedKnownError != null);
        m.put("suggested_fix", i.matchedKnownError == null ? null : i.matchedKnownError.fix);
        m.put("occurrence_count", i.occurrenceCount);
        m.put("manual_retries", i.manualRetries);
        m.put("pending_fixes", i.pendingFixes);
        return m;
    }

    public static Map<String, Object> ticket(Ticket t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ticket_id", t.ticketId);
        m.put("incident", t.incident == null ? null : t.incident.incidentId);
        m.put("source", t.source);
        m.put("status", t.status);
        m.put("assignee", t.assignee);
        m.put("created_by", t.createdBy);
        m.put("resolved_by", t.resolvedBy);
        m.put("resolution_type", t.resolutionType);
        m.put("resolution_notes", t.resolutionNotes);
        m.put("timeline", t.timeline);
        m.put("title", t.title);
        m.put("description", t.description);
        m.put("created_at", t.createdAt);
        m.put("updated_at", t.updatedAt);
        m.put("resolved_at", t.resolvedAt);
        m.put("incident_error", t.incident == null ? null : t.incident.error);
        return m;
    }

    public static Map<String, Object> user(UserAccount u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", u.id);
        m.put("username", u.username);
        m.put("role", u.effectiveRole());
        m.put("is_superuser", u.superuser);
        return m;
    }
}
