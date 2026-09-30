package com.batchops.api;

import com.batchops.incident.IncidentService;
import com.batchops.persistence.Incident;
import com.batchops.persistence.IncidentRepository;
import com.batchops.persistence.UserAccount;
import com.batchops.security.Access;
import com.batchops.security.DepartmentScope;
import jakarta.persistence.criteria.JoinType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/incidents")
public class IncidentController {

    private static final Set<String> ORDERABLE = Set.of("created_at", "updated_at", "state", "severity", "resolved_at");

    private final IncidentRepository incidents;
    private final IncidentService service;
    private final DepartmentScope scope;

    public IncidentController(IncidentRepository incidents, IncidentService service, DepartmentScope scope) {
        this.incidents = incidents;
        this.service = service;
        this.scope = scope;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String state,
                                    @RequestParam(name = "upload_id", required = false) String uploadId,
                                    @RequestParam(name = "job_run", required = false) String jobRun,
                                    @RequestParam(required = false) String known, HttpServletRequest req) {
        Set<String> allowed = scope.restrictionFor(Access.current());
        Specification<Incident> spec = Specification.where(null);
        if (state != null && !state.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("state"), state));
        }
        if (uploadId != null && !uploadId.isBlank()) {
            UUID id = JobRunController.uuid(uploadId);
            spec = spec.and((r, q, cb) -> cb.equal(r.join("upload").get("uploadId"), id));
        }
        if (jobRun != null && !jobRun.isBlank()) {
            UUID id = JobRunController.uuid(jobRun);
            spec = spec.and((r, q, cb) -> cb.equal(r.join("jobRun", JoinType.LEFT).get("runId"), id));
        }
        if ("true".equals(known)) {
            spec = spec.and((r, q, cb) -> cb.isNotNull(r.get("matchedKnownError")));
        } else if ("false".equals(known)) {
            spec = spec.and((r, q, cb) -> cb.isNull(r.get("matchedKnownError")));
        }
        if (!allowed.isEmpty()) {
            spec = spec.and((r, q, cb) -> r.join("upload").get("department").in(allowed));
        }
        Page<Incident> page = incidents.findAll(spec, Paging.pageable(req, ORDERABLE, "-created_at"));
        return Paging.envelope(page, req, Views::incident);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        Incident i = incidents.findById(id).orElseThrow(() -> ApiException.notFound("Not found."));
        if (!scope.canSee(Access.current(), i.upload.department)) {
            throw ApiException.notFound("Not found.");
        }
        return Views.incident(i);
    }

    @PatchMapping({"/{id}/assign"})
    public Map<String, Object> assignPatch(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return assign(id, body);
    }

    @PostMapping("/{id}/assign")
    public Map<String, Object> assign(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        UserAccount u = Access.moderator();
        return Views.incident(service.assign(id, u, nn(body)));
    }

    @PatchMapping("/{id}/resolve")
    public Map<String, Object> resolvePatch(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return resolve(id, body);
    }

    @PostMapping("/{id}/resolve")
    public Map<String, Object> resolve(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        UserAccount u = Access.moderator();
        return Views.incident(service.resolve(id, u, nn(body)));
    }

    @PatchMapping("/{id}/analyze")
    public Map<String, Object> analyzePatch(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return analyze(id, body);
    }

    @PostMapping("/{id}/analyze")
    public Map<String, Object> analyze(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        UserAccount u = Access.moderator();
        return Views.incident(service.analyze(id, u, nn(body)));
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        UserAccount u = Access.moderator();
        service.retry(id, u, nn(body));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "requeued");
        m.put("incident_id", id.toString());
        return m;
    }

    @PostMapping("/{id}/archive")
    public Map<String, Object> archive(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        UserAccount u = Access.moderator();
        return Views.incident(service.archive(id, u, nn(body)));
    }

    /** New: approve destructive fixes (dedupe, clip scores, drop rows) that the engine will not run alone. */
    @PostMapping("/{id}/approve-fixes")
    public Map<String, Object> approveFixes(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        UserAccount u = Access.moderator();
        return Views.incident(service.approveFixes(id, u, nn(body)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Access.moderator();
        incidents.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private static Map<String, Object> nn(Map<String, Object> b) {
        return b == null ? new LinkedHashMap<>() : b;
    }
}
