package com.batchops.incident;

import com.batchops.api.ApiException;
import com.batchops.config.AppProperties;
import com.batchops.domain.FixAction;
import com.batchops.persistence.Incident;
import com.batchops.persistence.IncidentRepository;
import com.batchops.persistence.UserAccount;
import com.batchops.persistence.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Human-driven incident operations (assign, analyze, resolve, retry, archive, approve fixes). */
@Service
public class IncidentService {

    private final IncidentRepository incidents;
    private final UserRepository users;
    private final IncidentEngine engine;
    private final AppProperties props;

    public IncidentService(IncidentRepository incidents, UserRepository users, IncidentEngine engine,
                           AppProperties props) {
        this.incidents = incidents;
        this.users = users;
        this.engine = engine;
        this.props = props;
    }

    private Incident load(UUID id) {
        return incidents.findById(id).orElseThrow(() -> ApiException.notFound("Incident not found"));
    }

    @Transactional
    public Incident assign(UUID id, UserAccount actor, Map<String, Object> body) {
        Incident inc = load(id);
        String assignee = text(body, "assignee");
        if (actor.isAdmin()) {
            if (assignee.isEmpty()) {
                throw ApiException.badRequest("assignee required");
            }
            UserAccount target = users.findByUsernameIgnoreCase(assignee).orElse(null);
            if (target == null || !target.isModerator()) {
                throw ApiException.badRequest("assignee must be a moderator");
            }
            inc.assignee = target.username;
        } else {
            if (inc.assignee != null && !inc.assignee.isBlank() && !inc.assignee.equals(actor.username)) {
                throw ApiException.forbidden("incident already assigned");
            }
            inc.assignee = actor.username;
        }
        inc.state = "in_progress";
        inc.timeline = Timelines.with(inc.timeline, "Assigned to " + inc.assignee, actor.username, nullable(body, "notes"));
        inc.updatedAt = Instant.now();
        return incidents.save(inc);
    }

    @Transactional
    public Incident resolve(UUID id, UserAccount actor, Map<String, Object> body) {
        Incident inc = load(id);
        String resolvedBy = actor.username;
        inc.rootCause = nullable(body, "root_cause");
        inc.correctiveAction = nullable(body, "corrective_action");
        String report = nullable(body, "resolution_report");
        if (report != null) {
            inc.resolutionReport = report;
        }
        inc.state = "resolved";
        inc.resolvedBy = resolvedBy;
        inc.resolvedAt = Instant.now();
        inc.pendingFixes = new ArrayList<>();
        inc.timeline = Timelines.with(inc.timeline, "Incident resolved", resolvedBy,
                inc.resolutionReport != null ? inc.resolutionReport : nullable(body, "notes"));
        inc.updatedAt = Instant.now();
        incidents.save(inc);
        engine.resolveTickets(inc, resolvedBy, "automatic",
                inc.correctiveAction != null ? inc.correctiveAction : "Incident resolved");
        return inc;
    }

    @Transactional
    public Incident analyze(UUID id, UserAccount actor, Map<String, Object> body) {
        Incident inc = load(id);
        inc.analysisNotes = orKeep(body, "analysis_notes", inc.analysisNotes);
        inc.impactSummary = orKeep(body, "impact_summary", inc.impactSummary);
        inc.severity = orKeep(body, "severity", inc.severity);
        inc.category = orKeep(body, "category", inc.category);
        inc.detectionSource = orKeep(body, "detection_source", inc.detectionSource);
        inc.rootCause = orKeep(body, "root_cause", inc.rootCause);
        inc.correctiveAction = orKeep(body, "corrective_action", inc.correctiveAction);
        inc.resolutionReport = orKeep(body, "resolution_report", inc.resolutionReport);
        String stage = nullable(body, "rca_stage");
        String notes = nullable(body, "notes");
        if (notes == null) {
            notes = stage != null ? "Stage set to " + stage
                    : (nullable(body, "analysis_notes") != null ? nullable(body, "analysis_notes")
                    : "Analysis details updated");
        }
        inc.timeline = Timelines.with(inc.timeline, stage != null ? "RCA updated" : "Analysis updated",
                actor.username, notes);
        inc.updatedAt = Instant.now();
        return incidents.save(inc);
    }

    @Transactional
    public Incident retry(UUID id, UserAccount actor, Map<String, Object> body) {
        Incident inc = load(id);
        if (inc.manualRetries >= props.pipeline().maxManualRetries()) {
            throw ApiException.conflict("Manual retry limit reached (" + props.pipeline().maxManualRetries() + ")");
        }
        inc.manualRetries++;
        inc.state = "in_progress";
        inc.timeline = Timelines.with(inc.timeline, "Manual retry requested", actor.username, nullable(body, "notes"));
        inc.updatedAt = Instant.now();
        engine.requeueNow(inc.upload);
        return incidents.save(inc);
    }

    @Transactional
    public Incident archive(UUID id, UserAccount actor, Map<String, Object> body) {
        Incident inc = load(id);
        inc.archivedAt = Instant.now();
        inc.state = "resolved";
        inc.resolvedAt = inc.archivedAt;
        inc.timeline = Timelines.with(inc.timeline, "Incident archived", actor.username, nullable(body, "notes"));
        inc.updatedAt = Instant.now();
        return incidents.save(inc);
    }

    /** A moderator explicitly approves the destructive fixes the engine refused to run on its own. */
    @Transactional
    public Incident approveFixes(UUID id, UserAccount actor, Map<String, Object> body) {
        Incident inc = load(id);
        if (inc.pendingFixes == null || inc.pendingFixes.isEmpty()) {
            throw ApiException.conflict("No fixes are waiting for approval");
        }
        List<FixAction> actions = new ArrayList<>();
        for (String name : inc.pendingFixes) {
            FixAction.parse(name).ifPresent(actions::add);
        }
        inc.timeline = Timelines.with(inc.timeline, "Fixes approved", actor.username, String.join(", ", inc.pendingFixes));
        boolean applied = engine.applyFixes(inc, inc.upload, actions, actor.username);
        if (!applied) {
            throw ApiException.conflict("The approved fixes made no change to the data");
        }
        inc.updatedAt = Instant.now();
        return incidents.save(inc);
    }

    // ------------------------------------------------------------------ body helpers

    static String text(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? "" : String.valueOf(v).trim();
    }

    static String nullable(Map<String, Object> body, String key) {
        String s = text(body, key);
        return s.isEmpty() ? null : s;
    }

    static String orKeep(Map<String, Object> body, String key, String current) {
        String s = nullable(body, key);
        return s == null ? current : s;
    }
}
