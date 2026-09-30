package com.batchops.api;

import com.batchops.domain.CronSupport;
import com.batchops.persistence.Job;
import com.batchops.persistence.JobRepository;
import com.batchops.schedule.JobExecutor;
import com.batchops.schedule.TaskRegistry;
import com.batchops.security.Access;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Set<String> ORDERABLE = Set.of("name", "created_at", "updated_at", "id");

    private final JobRepository jobs;
    private final JobExecutor executor;
    private final TaskRegistry registry;

    public JobController(JobRepository jobs, JobExecutor executor, TaskRegistry registry) {
        this.jobs = jobs;
        this.executor = executor;
        this.registry = registry;
    }

    @GetMapping
    public Map<String, Object> list(HttpServletRequest req) {
        Access.current();
        Page<Job> page = jobs.findAll(Paging.pageable(req, ORDERABLE, "name"));
        return Paging.envelope(page, req, Views::job);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable Long id) {
        Access.current();
        return Views.job(jobs.findById(id).orElseThrow(() -> ApiException.notFound("Not found.")));
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        Access.admin();
        Job j = new Job();
        apply(j, body, true);
        return ResponseEntity.status(HttpStatus.CREATED).body(Views.job(jobs.save(j)));
    }

    @PatchMapping("/{id}")
    @Transactional
    public Map<String, Object> patch(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Access.admin();
        Job j = jobs.findById(id).orElseThrow(() -> ApiException.notFound("Not found."));
        apply(j, body, false);
        return Views.job(jobs.save(j));
    }

    @PutMapping("/{id}")
    public Map<String, Object> put(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        return patch(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Access.admin();
        jobs.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** Any signed-in user may trigger a job (as before); the run is recorded and executes asynchronously. */
    @PostMapping("/{id}/trigger")
    public Map<String, Object> trigger(@PathVariable Long id, @RequestBody(required = false) Map<String, Object> payload) {
        Access.current();
        Job j = jobs.findById(id).orElseThrow(() -> ApiException.notFound("Not found."));
        if (payload != null && payload.containsKey("task")) {
            throw ApiException.badRequest("the task of a job cannot be overridden at trigger time");
        }
        executor.submit(j, payload);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "queued");
        m.put("job_id", j.id);
        return m;
    }

    @SuppressWarnings("unchecked")
    private void apply(Job j, Map<String, Object> body, boolean creating) {
        if (body.containsKey("name")) {
            String n = String.valueOf(body.get("name")).trim();
            if (n.isEmpty()) {
                throw ApiException.badRequest("name is required");
            }
            j.name = n;
        } else if (creating) {
            throw ApiException.badRequest("name is required");
        }
        if (body.containsKey("job_type")) {
            String t = String.valueOf(body.get("job_type"));
            if (!Set.of("shell", "http", "python").contains(t)) {
                throw ApiException.badRequest("job_type must be shell, http or python");
            }
            j.jobType = t;
        }
        if (body.get("config") instanceof Map<?, ?> cfg) {
            Object task = cfg.get("task");
            if (task == null || registry.find(String.valueOf(task)).isEmpty()) {
                throw ApiException.badRequest("config.task must be one of " + registry.names());
            }
            j.config = new LinkedHashMap<>((Map<String, Object>) cfg);
        } else if (creating) {
            throw ApiException.badRequest("config.task is required; registered tasks: " + registry.names());
        }
        if (body.containsKey("schedule_cron")) {
            Object c = body.get("schedule_cron");
            String cron = c == null ? null : String.valueOf(c).trim();
            if (cron != null && !cron.isEmpty()) {
                try {
                    CronExpression.parse(CronSupport.toSpring(cron));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("invalid cron expression: " + cron);
                }
            }
            j.scheduleCron = cron == null || cron.isEmpty() ? null : cron;
        }
        j.updatedAt = Instant.now();
    }
}
