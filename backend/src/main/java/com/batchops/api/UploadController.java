package com.batchops.api;

import com.batchops.config.AppProperties;
import com.batchops.persistence.Upload;
import com.batchops.persistence.UploadRepository;
import com.batchops.persistence.UserAccount;
import com.batchops.pipeline.UploadService;
import com.batchops.security.Access;
import com.batchops.security.DepartmentScope;
import com.batchops.storage.ObjectStore;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private static final Set<String> ORDERABLE = Set.of("received_at", "status", "department", "filename");

    private final UploadRepository uploads;
    private final UploadService service;
    private final ObjectStore store;
    private final DepartmentScope scope;
    private final AppProperties props;
    private final ObjectMapper mapper;

    public UploadController(UploadRepository uploads, UploadService service, ObjectStore store, DepartmentScope scope,
                            AppProperties props, ObjectMapper mapper) {
        this.uploads = uploads;
        this.service = service;
        this.store = store;
        this.scope = scope;
        this.props = props;
        this.mapper = mapper;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String status,
                                    @RequestParam(required = false) String department, HttpServletRequest req) {
        UserAccount user = Access.current();
        Specification<Upload> spec = Specification.where(null);
        if (status != null && !status.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("status"), status));
        }
        if (department != null && !department.isBlank()) {
            spec = spec.and((r, q, cb) -> cb.equal(r.get("department"), department));
        }
        Set<String> allowed = scope.restrictionFor(user);
        if (!allowed.isEmpty()) {
            spec = spec.and((r, q, cb) -> r.get("department").in(allowed));
        }
        Page<Upload> page = uploads.findAll(spec, Paging.pageable(req, ORDERABLE, "-received_at"));
        return Paging.envelope(page, req, Views::upload);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return Views.upload(find(id));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> create(@RequestParam(value = "file", required = false) MultipartFile file,
                                                      @RequestParam(value = "department", defaultValue = "General") String department,
                                                      @RequestParam(value = "notes", defaultValue = "") String notes,
                                                      @RequestParam(value = "process_mode", required = false) String processMode,
                                                      @RequestParam(value = "process_config", required = false) String processConfig)
            throws IOException {
        UserAccount user = Access.current();
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("file is required");
        }
        long max = props.pipeline().maxUploadMb() * 1024L * 1024L;
        if (file.getSize() > max) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds " + props.pipeline().maxUploadMb() + " MB");
        }
        String dept = department.isBlank() ? "General" : department.trim();
        if (!scope.canSee(user, dept)) {
            throw ApiException.forbidden("you are not allowed to upload to department " + dept);
        }
        UploadService.Result r = service.ingest(file.getBytes(), file.getOriginalFilename(), file.getContentType(), dept,
                notes, processMode, parseConfig(processConfig), user.username, true);
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK).body(Views.upload(r.upload()));
    }

    @PostMapping("/{id}/retry")
    public Map<String, Object> retry(@PathVariable UUID id) {
        find(id);
        Upload u = service.retry(id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "requeued");
        m.put("upload_id", u.uploadId.toString());
        return m;
    }

    @GetMapping("/{id}/source")
    public ResponseEntity<byte[]> source(@PathVariable UUID id) {
        Upload u = find(id);
        try {
            byte[] data = store.get(u.sourceKey);
            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safe(u.filename) + "\"")
                    .contentType(u.mimeType == null || u.mimeType.isBlank() ? MediaType.APPLICATION_OCTET_STREAM
                            : MediaType.parseMediaType(u.mimeType))
                    .body(data);
        } catch (FileNotFoundException e) {
            throw ApiException.notFound("Source file not found");
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unable to read source file");
        }
    }

    @PatchMapping("/{id}")
    public Map<String, Object> patch(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        Access.admin();
        return Views.upload(service.update(id, body));
    }

    @PutMapping("/{id}")
    public Map<String, Object> put(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return patch(id, body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        Access.admin();
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    private Upload find(UUID id) {
        UserAccount user = Access.current();
        Upload u = uploads.findById(id).orElseThrow(() -> ApiException.notFound("Not found."));
        if (!scope.canSee(user, u.department)) {
            throw ApiException.notFound("Not found.");
        }
        return u;
    }

    private Map<String, Object> parseConfig(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> m = mapper.readValue(raw, new TypeReference<>() { });
            m.remove("_encoding"); // internal key, not user-settable
            return m;
        } catch (IOException e) {
            return new LinkedHashMap<>();
        }
    }

    private static String safe(String name) {
        return name == null ? "file" : name.replace("\"", "").replace("\r", "").replace("\n", "");
    }
}
