package com.batchops.api;

import com.batchops.persistence.JobRun;
import com.batchops.persistence.JobRunRepository;
import com.batchops.security.Access;
import com.batchops.security.DepartmentScope;
import jakarta.persistence.criteria.JoinType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/job-runs")
public class JobRunController {

    private static final Set<String> ORDERABLE = Set.of("started_at", "finished_at", "status", "duration_ms", "id");

    private final JobRunRepository runs;
    private final DepartmentScope scope;

    public JobRunController(JobRunRepository runs, DepartmentScope scope) {
        this.runs = runs;
        this.scope = scope;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(name = "upload_id", required = false) String uploadId,
                                    @RequestParam(required = false) String status, HttpServletRequest req) {
        Set<String> allowed = scope.restrictionFor(Access.current());
        Specification<JobRun> spec = Specification.where(null);
        if (uploadId != null && !uploadId.isBlank()) {
            UUID id = uuid(uploadId);
            spec = spec.and((r, q, cb) -> cb.equal(r.join("upload", JoinType.LEFT).get("uploadId"), id));
        }
        if (status != null && !status.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        }
        if (!allowed.isEmpty()) {
            spec = spec.and((r, q, cb) -> cb.or(cb.isNull(r.get("upload")),
                    r.join("upload", JoinType.LEFT).get("department").in(allowed)));
        }
        Page<JobRun> page = runs.findAll(spec, Paging.pageable(req, ORDERABLE, "-started_at"));
        return Paging.envelope(page, req, Views::jobRun);
    }

    @GetMapping("/{runId}")
    public Map<String, Object> get(@PathVariable UUID runId) {
        JobRun r = runs.findByRunId(runId).orElseThrow(() -> ApiException.notFound("Not found."));
        if (r.upload != null && !scope.canSee(Access.current(), r.upload.department)) {
            throw ApiException.notFound("Not found.");
        }
        return Views.jobRun(r);
    }

    static UUID uuid(String s) {
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("invalid id: " + s);
        }
    }
}
