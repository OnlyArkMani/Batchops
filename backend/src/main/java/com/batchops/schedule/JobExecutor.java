package com.batchops.schedule;

import com.batchops.persistence.Job;
import com.batchops.persistence.JobRun;
import com.batchops.persistence.JobRunRepository;
import com.batchops.pipeline.JobRunService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Runs a job's registered task and records the outcome as a job run. */
@Service
public class JobExecutor {

    private static final Logger log = LoggerFactory.getLogger(JobExecutor.class);

    private final TaskRegistry registry;
    private final JobRunService runs;
    private final JobRunRepository runRepo;
    private final TransactionTemplate tx;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);

    public JobExecutor(TaskRegistry registry, JobRunService runs, JobRunRepository runRepo, TransactionTemplate tx) {
        this.registry = registry;
        this.runs = runs;
        this.runRepo = runRepo;
        this.tx = tx;
    }

    @PreDestroy
    void shutdown() {
        pool.shutdown();
    }

    /** Creates a queued run synchronously (so the caller can return its id) and executes it in the background. */
    public JobRun submit(Job job, Map<String, Object> payload) {
        Map<String, Object> cfg = new LinkedHashMap<>(job.config == null ? Map.of() : job.config);
        if (payload != null) {
            cfg.putAll(payload);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("config", cfg);
        JobRun run = tx.execute(s -> {
            JobRun r = runs.genericRun(job, details);
            r.status = "queued";
            return runRepo.save(r);
        });
        pool.submit(() -> execute(run.id, cfg));
        return run;
    }

    /** Runs inline; used by the scheduler (already on its own thread). */
    public void runNow(Job job, Map<String, Object> payload) {
        JobRun run = submit(job, payload);
        log.debug("Job {} queued as run {}", job.name, run.runId);
    }

    private void execute(Long runId, Map<String, Object> cfg) {
        JobRun run = runRepo.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        tx.executeWithoutResult(s -> {
            JobRun r = runRepo.findById(runId).orElseThrow();
            r.status = "running";
            r.startedAt = java.time.Instant.now();
            runRepo.save(r);
        });
        String status = "success";
        String logs;
        int exit = 0;
        try {
            String taskName = cfg.get("task") == null ? null : String.valueOf(cfg.get("task"));
            Task task = registry.find(taskName).orElseThrow(() -> new IllegalArgumentException(
                    "Unknown task '" + taskName + "'. Registered tasks: " + registry.names()));
            List<Object> args = cfg.get("args") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
            @SuppressWarnings("unchecked")
            Map<String, Object> kwargs = cfg.get("kwargs") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            logs = "Job executed successfully. Result: " + task.run(args, kwargs);
        } catch (Exception e) {
            status = "failed";
            exit = 1;
            logs = "Scheduled job failed: " + e.getMessage();
            log.warn("Job run {} failed", runId, e);
        }
        final String fStatus = status;
        final String fLogs = logs;
        final int fExit = exit;
        tx.executeWithoutResult(s -> {
            JobRun r = runRepo.findById(runId).orElseThrow();
            runs.finish(r, fStatus, fLogs, fExit);
        });
    }
}
