package com.batchops.incident;

import com.batchops.config.AppProperties;
import com.batchops.domain.AutoFix;
import com.batchops.domain.FixAction;
import com.batchops.domain.IncidentFingerprint;
import com.batchops.domain.KnownErrorCatalog;
import com.batchops.domain.Table;
import com.batchops.domain.Topics;
import com.batchops.domain.UploadStatus;
import com.batchops.events.EventHeaders;
import com.batchops.events.OutboxService;
import com.batchops.events.StageMessage;
import com.batchops.io.TableIO;
import com.batchops.io.TableLoader;
import com.batchops.ops.Metrics;
import com.batchops.persistence.Incident;
import com.batchops.persistence.IncidentRepository;
import com.batchops.persistence.JobRun;
import com.batchops.persistence.KnownError;
import com.batchops.persistence.KnownErrorRepository;
import com.batchops.persistence.Ticket;
import com.batchops.persistence.TicketRepository;
import com.batchops.persistence.Upload;
import com.batchops.persistence.UploadRepository;
import com.batchops.pipeline.ArtifactKeys;
import com.batchops.storage.ObjectStore;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns pipeline failures into incidents and tries to heal them.
 *
 * <ul>
 *   <li>Incidents are de-duplicated by fingerprint, so a flapping failure bumps a counter instead of
 *       creating a wall of tickets.</li>
 *   <li>Known errors (regex catalogue) drive severity, root cause and an optional auto-fix.</li>
 *   <li>Non-destructive fixes run automatically; destructive ones (dedupe, clip, drop rows) wait for a
 *       moderator unless the deployment explicitly allows them.</li>
 *   <li>Fixes never touch the original upload: they write a new versioned artifact and re-run.</li>
 * </ul>
 */
@Service
public class IncidentEngine {

    private static final Logger log = LoggerFactory.getLogger(IncidentEngine.class);
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");

    private final IncidentRepository incidents;
    private final TicketRepository tickets;
    private final KnownErrorRepository known;
    private final UploadRepository uploads;
    private final OutboxService outbox;
    private final ObjectStore store;
    private final AppProperties props;
    private final Metrics metrics;

    public IncidentEngine(IncidentRepository incidents, TicketRepository tickets, KnownErrorRepository known,
                          UploadRepository uploads, OutboxService outbox, ObjectStore store, AppProperties props,
                          Metrics metrics) {
        this.incidents = incidents;
        this.tickets = tickets;
        this.known = known;
        this.uploads = uploads;
        this.outbox = outbox;
        this.store = store;
        this.props = props;
        this.metrics = metrics;
    }

    // ------------------------------------------------------------------ open / dedupe

    @Transactional(propagation = Propagation.MANDATORY)
    public Incident openOrBump(Upload u, JobRun run, String step, String error) {
        String fingerprint = IncidentFingerprint.of(u.uploadId.toString(), step, error);
        Optional<Incident> existing = incidents.findFirstByFingerprintAndStateNot(fingerprint, "resolved");
        Incident inc;
        if (existing.isPresent()) {
            inc = existing.get();
            inc.occurrenceCount++;
            inc.jobRun = run;
            inc.error = error;
            inc.timeline = Timelines.with(inc.timeline, "Failure recurred", "engine",
                    "Occurrence #" + inc.occurrenceCount + ": " + Timelines.trunc(error, 280));
            inc.updatedAt = Instant.now();
        } else {
            KnownError matched = match(error);
            inc = new Incident();
            inc.upload = u;
            inc.jobRun = run;
            inc.fingerprint = fingerprint;
            inc.error = error;
            inc.state = "open";
            inc.matchedKnownError = matched;
            inc.detectionSource = "engine";
            if (matched != null && matched.fix != null) {
                inc.rootCause = str(matched.fix.get("root_cause"), str(matched.fix.get("rca"), null));
                inc.correctiveAction = str(matched.fix.get("corrective_action"), str(matched.fix.get("action"), null));
            }
            inc.timeline = Timelines.with(null, "Incident detected", "engine", Timelines.trunc(error, 280));
            incidents.save(inc);
            applyTriage(inc, matched);
            createSystemTicket(inc, run, error);
            metrics.incident("open");
        }
        incidents.save(inc);
        remediate(inc, u);
        incidents.save(inc);
        return inc;
    }

    private void createSystemTicket(Incident inc, JobRun run, String error) {
        Ticket t = new Ticket();
        t.incident = inc;
        t.source = "system";
        t.status = "in_progress";
        t.assignee = "engine";
        t.title = "Auto ticket: " + (run != null && run.job != null ? run.job.name : "pipeline");
        t.description = Timelines.trunc(error, 500);
        t.timeline = Timelines.with(null, "System ticket created", "system", null);
        tickets.save(t);
    }

    /** First match wins, newest definition first; a broken regex in the database is skipped. */
    public KnownError match(String error) {
        for (KnownError ke : known.findByActiveTrueOrderByUpdatedAtDesc()) {
            if (KnownErrorCatalog.matches(ke.pattern, error)) {
                return ke;
            }
        }
        return null;
    }

    private void applyTriage(Incident inc, KnownError matched) {
        if (matched == null) {
            inc.timeline = Timelines.with(inc.timeline, "Unknown incident awaiting manual triage", "engine", null);
            return;
        }
        Map<String, Object> fix = matched.fix == null ? Map.of() : matched.fix;
        if (fix.get("severity") != null) {
            inc.severity = String.valueOf(fix.get("severity"));
        }
        if (fix.get("category") != null) {
            inc.category = String.valueOf(fix.get("category"));
        }
        if (fix.get("resolution_report") != null && inc.resolutionReport == null) {
            inc.resolutionReport = String.valueOf(fix.get("resolution_report"));
        }
    }

    // ------------------------------------------------------------------ remediation

    private void remediate(Incident inc, Upload u) {
        KnownError ke = inc.matchedKnownError;
        if (ke == null || "resolved".equals(inc.state)) {
            return;
        }
        if (inc.assignee != null && !inc.assignee.isBlank()) {
            return; // a human owns it now
        }
        Map<String, Object> fix = ke.fix == null ? Map.of() : ke.fix;
        Map<String, Object> retryCfg = asMap(fix.get("auto_retry"));
        boolean retryEnabled = Boolean.TRUE.equals(retryCfg.get("enabled"));
        if (retryEnabled && retryCfg.get("max") instanceof Number n) {
            inc.maxAutoRetries = n.intValue();
        }
        List<FixAction> actions = KnownErrorCatalog.actionsOf(fix);
        if (actions.isEmpty() && !retryEnabled) {
            actions = FixAction.fromError(inc.error);
        }

        if (retryEnabled) {
            if (inc.autoRetryCount >= inc.maxAutoRetries) {
                inc.timeline = Timelines.with(inc.timeline, "Auto retry limit reached", "engine",
                        "Max retries (" + inc.maxAutoRetries + ") exhausted");
                return;
            }
            long delay = retryCfg.get("delay_seconds") instanceof Number n ? n.longValue() : 60L;
            inc.autoRetryCount++;
            inc.state = "in_progress";
            inc.timeline = Timelines.with(inc.timeline, "Auto retry scheduled", "engine",
                    "Retry #" + inc.autoRetryCount + " queued in " + delay + "s");
            requeueAfter(u, delay);
            return;
        }
        if (actions.isEmpty()) {
            inc.timeline = Timelines.with(inc.timeline, "Known error tagged", "engine", "Matched " + ke.name);
            return;
        }
        if (inc.autoRetryCount >= inc.maxAutoRetries) {
            inc.timeline = Timelines.with(inc.timeline, "Auto-remediation skipped", "engine",
                    "Retry limit reached (" + inc.autoRetryCount + "/" + inc.maxAutoRetries + ")");
            return;
        }
        FixAction.Plan plan = FixAction.plan(actions, props.pipeline().allowDestructiveAutoFix());
        if (!plan.needsApproval().isEmpty()) {
            inc.pendingFixes = plan.needsApproval().stream().map(FixAction::wire).collect(Collectors.toList());
            inc.timeline = Timelines.with(inc.timeline, "Fix awaiting approval", "engine",
                    "Destructive fix(es) need a moderator: " + String.join(", ", inc.pendingFixes));
        }
        if (!plan.auto().isEmpty()) {
            applyFixes(inc, u, plan.auto(), "engine");
        }
    }

    /**
     * Applies fixes to the latest artifact, writes the result as a new version and re-runs the pipeline.
     * @return true when the pipeline was re-queued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean applyFixes(Incident inc, Upload u, List<FixAction> actions, String actor) {
        try {
            boolean reencode = actions.contains(FixAction.REENCODE_UTF8);
            String key = u.currentKey == null || u.currentKey.isBlank() ? ArtifactKeys.source(u) : u.currentKey;
            Charset cs = reencode ? WINDOWS_1252 : StandardCharsets.UTF_8;
            Table table = TableLoader.load(key, store.get(key), new TableLoader.Options(cs));

            List<FixAction> tableActions = new ArrayList<>(actions);
            tableActions.remove(FixAction.REENCODE_UTF8);
            AutoFix.Outcome out = AutoFix.apply(table, tableActions, props.pipeline().scoreMin(),
                    props.pipeline().scoreMax());
            boolean convert = actions.contains(FixAction.CONVERT_TO_CSV) && !key.toLowerCase().endsWith(".csv");
            if (!out.changed() && !reencode && !convert) {
                inc.timeline = Timelines.with(inc.timeline, "Auto-fix made no change", actor,
                        "Actions: " + names(actions));
                return false;
            }

            int newVersion = u.version + 1;
            String newKey = ArtifactKeys.fixed(u, newVersion);
            store.put(newKey, TableIO.toCsvBytes(out.table()), "text/csv");
            u.currentKey = newKey;
            u.version = newVersion;
            Map<String, Object> cfg = new LinkedHashMap<>(u.processConfig == null ? Map.of() : u.processConfig);
            cfg.remove("_encoding"); // the fixed artifact is UTF-8
            u.processConfig = cfg;
            moveToProcessing(u);
            uploads.save(u);

            List<String> notes = new ArrayList<>(out.notes());
            if (reencode) {
                notes.add("Re-encoded source from windows-1252 to UTF-8");
            }
            if (convert) {
                notes.add("Converted source to CSV");
            }
            inc.autoRetryCount++;
            inc.state = "in_progress";
            inc.pendingFixes = new ArrayList<>(inc.pendingFixes == null ? List.of() : inc.pendingFixes);
            inc.pendingFixes.removeIf(p -> actions.stream().anyMatch(a -> a.wire().equals(p)));
            inc.timeline = Timelines.with(inc.timeline, "Auto-remediation applied", actor,
                    String.join("; ", notes) + " (artifact v" + newVersion + ")");
            outbox.publish(Topics.RECEIVED, u.uploadId, newVersion, 1);
            return true;
        } catch (Exception e) {
            log.warn("Auto-fix failed for upload {}: {}", u.uploadId, e.toString());
            inc.timeline = Timelines.with(inc.timeline, "Auto-fix could not be applied", actor,
                    Timelines.trunc(e.getMessage(), 280));
            return false;
        }
    }

    /** Re-queues the pipeline later, reusing the Kafka retry tiers so the delay survives restarts. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requeueAfter(Upload u, long delaySeconds) {
        int newVersion = u.version + 1;
        u.version = newVersion;
        moveToProcessing(u);
        uploads.save(u);
        String topic = delaySeconds <= 5 ? Topics.RETRY_5S : delaySeconds <= 60 ? Topics.RETRY_1M : Topics.RETRY_10M;
        Map<String, String> h = new LinkedHashMap<>();
        h.put(EventHeaders.ATTEMPT, "1");
        h.put(EventHeaders.ORIGIN_TOPIC, Topics.RECEIVED);
        h.put(EventHeaders.NOT_BEFORE, Long.toString(Instant.now().plusSeconds(delaySeconds).toEpochMilli()));
        outbox.publish(topic, u.uploadId.toString(), StageMessage.payloadOf(u.uploadId, newVersion), h);
    }

    /** Immediate re-run of the pipeline on a fresh version (manual retry, DLQ replay). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requeueNow(Upload u) {
        u.version = u.version + 1;
        moveToProcessing(u);
        uploads.save(u);
        outbox.publish(Topics.RECEIVED, u.uploadId, u.version, 1);
    }

    private void moveToProcessing(Upload u) {
        UploadStatus cur = UploadStatus.parse(u.status);
        if (cur.canMoveTo(UploadStatus.PROCESSING)) {
            u.status = UploadStatus.PROCESSING.wire();
        }
    }

    // ------------------------------------------------------------------ auto resolve

    /**
     * After a successful publish: resolve this upload's <em>known</em>, unassigned incidents. Incidents
     * a person has picked up (or unknown ones) stay open so a human can confirm the outcome.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void autoResolveAfterSuccess(Upload u) {
        List<Incident> open = incidents.findByUploadUploadIdAndStateIn(u.uploadId, List.of("open", "in_progress"));
        for (Incident inc : open) {
            boolean unassigned = inc.assignee == null || inc.assignee.isBlank();
            if (inc.matchedKnownError == null || !unassigned) {
                continue;
            }
            inc.state = "resolved";
            inc.resolvedBy = "engine";
            inc.resolvedAt = Instant.now();
            inc.pendingFixes = new ArrayList<>();
            if (inc.resolutionReport == null || inc.resolutionReport.isBlank()) {
                inc.resolutionReport = "Auto-resolved after successful remediation.";
            }
            inc.timeline = Timelines.with(inc.timeline, "Incident auto-resolved", "engine",
                    "Pipeline completed successfully after auto-remediation.");
            incidents.save(inc);
            resolveTickets(inc, "engine", "automatic", "Auto-resolved with incident");
            metrics.incident("resolved");
        }
    }

    public void resolveTickets(Incident inc, String by, String type, String notes) {
        for (Ticket t : tickets.findByIncidentIncidentIdAndStatusIn(inc.incidentId, List.of("open", "in_progress"))) {
            t.status = "resolved";
            t.resolvedBy = by;
            t.resolutionType = type;
            t.resolutionNotes = notes;
            t.resolvedAt = Instant.now();
            t.updatedAt = t.resolvedAt;
            t.timeline = Timelines.with(t.timeline, "Ticket resolved by " + by + " (" + type + ")", by, notes);
            tickets.save(t);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static String names(List<FixAction> a) {
        return a.stream().map(FixAction::wire).collect(Collectors.joining(", "));
    }

    private static String str(Object o, String fallback) {
        return o == null ? fallback : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }
}
