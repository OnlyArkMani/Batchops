package com.batchops.pipeline;

import com.batchops.ops.Metrics;
import com.batchops.persistence.Job;
import com.batchops.persistence.JobRepository;
import com.batchops.persistence.JobRun;
import com.batchops.persistence.JobRunRepository;
import com.batchops.persistence.Upload;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** Keeps the job_runs rows the UI shows: one run per pipeline attempt, with a step trail. */
@Service
public class JobRunService {

    public static final String PIPELINE_JOB = "results_pipeline";

    private final JobRepository jobs;
    private final JobRunRepository runs;
    private final Metrics metrics;

    public JobRunService(JobRepository jobs, JobRunRepository runs, Metrics metrics) {
        this.jobs = jobs;
        this.runs = runs;
        this.metrics = metrics;
    }

    public Job jobNamed(String name) {
        return jobs.findByName(name).orElseGet(() -> {
            Job j = new Job();
            j.name = name;
            j.jobType = "python";
            return jobs.save(j);
        });
    }

    /** The open run for this upload's current pipeline attempt, created on first use. */
    public JobRun pipelineRun(Upload u) {
        Optional<JobRun> open = runs.findFirstByUploadUploadIdAndJobNameAndFinishedAtIsNullOrderByIdDesc(
                u.uploadId, PIPELINE_JOB);
        if (open.isPresent()) {
            return open.get();
        }
        JobRun r = new JobRun();
        r.job = jobNamed(PIPELINE_JOB);
        r.upload = u;
        r.status = "running";
        r.startedAt = Instant.now();
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("version", u.version);
        d.put("steps", new ArrayList<>());
        r.details = d;
        return runs.save(r);
    }

    public JobRun genericRun(Job job, Map<String, Object> details) {
        JobRun r = new JobRun();
        r.job = job;
        r.status = "running";
        r.startedAt = Instant.now();
        r.details = details == null ? new LinkedHashMap<>() : new LinkedHashMap<>(details);
        return runs.save(r);
    }

    @SuppressWarnings("unchecked")
    public void step(JobRun r, String step, String note) {
        Map<String, Object> d = new LinkedHashMap<>(r.details == null ? Map.of() : r.details);
        List<Object> steps = new ArrayList<>(d.get("steps") instanceof List<?> l ? (List<Object>) l : List.of());
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("step", step);
        s.put("at", Instant.now().toString());
        s.put("note", note);
        steps.add(s);
        d.put("steps", steps);
        r.details = d;
        r.logs = append(r.logs, step + (note == null || note.isBlank() ? "" : ": " + note));
        runs.save(r);
    }

    public void markRetrying(JobRun r, int attempt, String error) {
        r.status = "retrying";
        r.retryCount = attempt;
        r.logs = append(r.logs, "retry " + attempt + " scheduled after transient error: " + error);
        runs.save(r);
    }

    public void finish(JobRun r, String status, String logs, int exitCode) {
        r.status = status;
        r.finishedAt = Instant.now();
        r.exitCode = exitCode;
        r.logs = cap(append(r.logs, logs));
        if (r.startedAt != null) {
            r.durationMs = (int) Duration.between(r.startedAt, r.finishedAt).toMillis();
        }
        runs.save(r);
        metrics.jobRun(r.job.name, status, r.durationMs == null ? 0 : r.durationMs);
    }

    private static String append(String current, String line) {
        if (line == null || line.isBlank()) {
            return current == null ? "" : current;
        }
        return current == null || current.isBlank() ? line : current + "\n" + line;
    }

    private static String cap(String s) {
        return s != null && s.length() > 20000 ? s.substring(0, 20000) : s;
    }
}
