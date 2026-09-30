package com.batchops.schedule;

import com.batchops.io.TableIO;
import com.batchops.mail.MailService;
import com.batchops.persistence.DepartmentRecord;
import com.batchops.persistence.DepartmentRecordRepository;
import com.batchops.persistence.DepartmentSource;
import com.batchops.persistence.DepartmentSourceRepository;
import com.batchops.persistence.IncidentRepository;
import com.batchops.persistence.JobRunRepository;
import com.batchops.persistence.UploadRepository;
import com.batchops.persistence.UserRepository;
import com.batchops.pipeline.UploadService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** The scheduled tasks, ported from automation/tasks.py and registered by name. */
@Component
public class Tasks {

    private static final Logger log = LoggerFactory.getLogger(Tasks.class);
    private static final int PER_SOURCE_LIMIT = 250;

    private final TaskRegistry registry;
    private final DepartmentSourceRepository sources;
    private final DepartmentRecordRepository records;
    private final UploadRepository uploads;
    private final IncidentRepository incidents;
    private final JobRunRepository runs;
    private final UserRepository users;
    private final UploadService uploadService;
    private final MailService mail;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public Tasks(TaskRegistry registry, DepartmentSourceRepository sources, DepartmentRecordRepository records,
                 UploadRepository uploads, IncidentRepository incidents, JobRunRepository runs,
                 UserRepository users, UploadService uploadService, MailService mail, JdbcTemplate jdbc,
                 TransactionTemplate tx) {
        this.registry = registry;
        this.sources = sources;
        this.records = records;
        this.uploads = uploads;
        this.incidents = incidents;
        this.runs = runs;
        this.users = users;
        this.uploadService = uploadService;
        this.mail = mail;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @PostConstruct
    void register() {
        registry.register("send_attendance_reminders", (a, k) -> attendanceReminders(arg(a, 0)));
        registry.register("send_system_status_digest", (a, k) -> statusDigest());
        registry.register("run_web_scrape", (a, k) -> webScrape(arg(a, 0) == null ? "admissions_portal" : arg(a, 0)));
        registry.register("schedule_file_ingest", (a, k) -> fileIngest(arg(a, 0) == null ? "General" : arg(a, 0)));
        registry.register("schedule_all_department_ingest", (a, k) -> allDepartmentIngest());
        registry.register("purge_old_records", (a, k) -> purge(a.isEmpty() ? 90 : Integer.parseInt(String.valueOf(a.get(0)))));
        registry.register("run_daily_backup", (a, k) -> dailyBackup());
    }

    private static String arg(List<Object> args, int i) {
        return args != null && args.size() > i && args.get(i) != null ? String.valueOf(args.get(i)) : null;
    }

    // ------------------------------------------------------------------ tasks

    String attendanceReminders(String grade) {
        Instant from = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = from.plus(Duration.ofDays(1));
        long count = uploads.findAll().stream()
                .filter(u -> !u.receivedAt.isBefore(from) && u.receivedAt.isBefore(to))
                .filter(u -> u.status.equals("pending") || u.status.equals("processing"))
                .filter(u -> grade == null || u.department.equalsIgnoreCase(grade))
                .count();
        return "Queued attendance reminders for " + count + " cohort(s) on " + LocalDate.now(ZoneOffset.UTC);
    }

    String statusDigest() {
        Instant now = Instant.now();
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        long open = incidents.countByStateIn(List.of("open", "in_progress"));
        long today = uploads.countByReceivedAtGreaterThanEqualAndReceivedAtLessThan(dayStart,
                dayStart.plus(Duration.ofDays(1)));
        String last = runs.findAll(org.springframework.data.domain.PageRequest.of(0, 1,
                org.springframework.data.domain.Sort.by("id").descending()))
                .stream().findFirst().map(r -> r.runId.toString()).orElse("-");
        String payload = "timestamp=" + now + ", open_incidents=" + open + ", todays_uploads=" + today
                + ", last_run=" + last;
        users.findAll().stream()
                .filter(u -> u.isAdmin() && u.emailVerified && u.email != null && !u.email.isBlank())
                .forEach(u -> mail.send(u.email, "BatchOps status digest", payload));
        return payload;
    }

    String webScrape(String target) {
        return "Scraped latest data source for " + target + " at " + Instant.now();
    }

    String fileIngest(String department) throws IOException {
        Optional<DepartmentSource> source = resolve(department);
        if (source.isEmpty()) {
            return "No department source found for " + department + ".";
        }
        DepartmentSource s = source.get();
        List<DepartmentRecord> rows = records.findBySourceIdOrderByRecordedAtDesc(s.id).stream()
                .limit(PER_SOURCE_LIMIT).collect(Collectors.toList());
        if (rows.isEmpty()) {
            return "No records available for " + s.name + ".";
        }
        ZonedDateTime ts = ZonedDateTime.now(ZoneOffset.UTC);
        String filename = s.code.toLowerCase() + "-ingest-" + ts.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".csv";
        List<String> header = List.of("student_id", "student_name", "class", "score", "attendance_percent",
                "status", "recorded_at");
        List<List<String>> body = new ArrayList<>();
        for (DepartmentRecord r : rows) {
            body.add(List.of(r.studentId, r.studentName, nz(r.className), num(r.score), num(r.attendancePercent),
                    nz(r.status), r.recordedAt == null ? "" : r.recordedAt.toString()));
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("source", s.code);
        cfg.put("source_name", s.name);
        uploadService.ingest(TableIO.toCsvBytes(header, body), filename, "text/csv", s.name,
                "Automated department ingest", "transform_gradebook", cfg, "scheduler", false);
        tx.executeWithoutResult(st -> {
            s.lastIngestedAt = ts.toInstant();
            sources.save(s);
        });
        return "Ingested " + rows.size() + " records from " + s.name + " and started processing.";
    }

    String allDepartmentIngest() throws IOException {
        List<DepartmentSource> active = sources.findByActiveTrueOrderByName();
        if (active.isEmpty()) {
            return "No active department sources to ingest.";
        }
        ZonedDateTime ts = ZonedDateTime.now(ZoneOffset.UTC);
        List<String> header = List.of("department", "student_id", "student_name", "class", "score",
                "attendance_percent", "status", "recorded_at");
        List<List<String>> body = new ArrayList<>();
        List<String> issues = new ArrayList<>();
        List<DepartmentSource> touched = new ArrayList<>();
        for (DepartmentSource s : active) {
            List<DepartmentRecord> rows = records.findBySourceIdOrderByRecordedAtDesc(s.id).stream()
                    .limit(PER_SOURCE_LIMIT).collect(Collectors.toList());
            if (rows.isEmpty()) {
                issues.add(s.name + ": no records");
                continue;
            }
            for (DepartmentRecord r : rows) {
                body.add(List.of(s.name, r.studentId, r.studentName, nz(r.className), num(r.score),
                        num(r.attendancePercent), nz(r.status), r.recordedAt == null ? "" : r.recordedAt.toString()));
            }
            touched.add(s);
        }
        String filename = "all-departments-ingest-" + ts.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".csv";
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("source", "ALL");
        cfg.put("source_names", active.stream().map(s -> s.name).collect(Collectors.toList()));
        cfg.put("per_source_limit", PER_SOURCE_LIMIT);
        uploadService.ingest(TableIO.toCsvBytes(header, body), filename, "text/csv", "All Departments",
                "Automated all-departments ingest", "transform_gradebook", cfg, "scheduler", false);
        tx.executeWithoutResult(st -> touched.forEach(s -> {
            s.lastIngestedAt = ts.toInstant();
            sources.save(s);
        }));
        String summary = "All departments ingest started (" + active.size() + " sources, " + body.size() + " records).";
        return issues.isEmpty() ? summary : summary + " Issues: " + String.join(", ", issues);
    }

    String purge(int days) {
        Instant threshold = Instant.now().minus(Duration.ofDays(days));
        int[] counts = new int[2];
        tx.executeWithoutResult(st -> {
            counts[0] = jdbc.update("delete from incidents where created_at < ?", java.sql.Timestamp.from(threshold));
            counts[1] = jdbc.update("delete from job_runs where finished_at < ?", java.sql.Timestamp.from(threshold));
            jdbc.update("delete from job_fires where fire_time < ?", java.sql.Timestamp.from(threshold));
            jdbc.update("delete from processed_events where processed_at < ?", java.sql.Timestamp.from(threshold));
            jdbc.update("delete from dead_letters where replayed_at is not null and replayed_at < ?",
                    java.sql.Timestamp.from(threshold));
        });
        return "Purged " + counts[1] + " job runs and " + counts[0] + " incidents older than " + days + " days.";
    }

    String dailyBackup() {
        // Hook for a real backup (pg_dump / object-store replication). Kept as a recorded no-op.
        return "Daily backup hook executed at " + Instant.now();
    }

    // ------------------------------------------------------------------ helpers

    private Optional<DepartmentSource> resolve(String department) {
        if (department == null || department.isBlank()) {
            return Optional.empty();
        }
        return sources.findAll().stream()
                .filter(s -> s.code.equalsIgnoreCase(department) || s.name.equalsIgnoreCase(department))
                .findFirst();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String num(java.math.BigDecimal v) {
        return v == null ? "" : v.toPlainString();
    }
}
