package com.batchops.api;

import com.batchops.io.TableIO;
import com.batchops.persistence.JobRun;
import com.batchops.persistence.JobRunRepository;
import com.batchops.persistence.Upload;
import com.batchops.persistence.UploadRepository;
import com.batchops.security.Access;
import com.batchops.security.DepartmentScope;
import com.batchops.storage.ObjectStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/reports")
public class ReportController {

    private final UploadRepository uploads;
    private final JobRunRepository runs;
    private final ObjectStore store;
    private final DepartmentScope scope;

    public ReportController(UploadRepository uploads, JobRunRepository runs, ObjectStore store, DepartmentScope scope) {
        this.uploads = uploads;
        this.runs = runs;
        this.store = store;
        this.scope = scope;
    }

    @GetMapping("/summary")
    public ResponseEntity<?> summary(@RequestParam(name = "upload_id", required = false) String uploadId,
                                     @RequestParam(name = "job_run_id", required = false) String jobRunId,
                                     @RequestParam(name = "format", defaultValue = "csv") String format) throws IOException {
        var user = Access.current();
        boolean pdf = "pdf".equalsIgnoreCase(format);
        Upload upload = null;
        JobRun run = null;

        if (jobRunId != null && !jobRunId.isBlank()) {
            UUID rid = tryUuid(jobRunId);
            run = rid == null ? null : runs.findByRunId(rid).orElse(null);
            if (run != null) {
                if (run.upload == null) {
                    throw ApiException.badRequest("Job run not associated with an upload yet");
                }
                upload = run.upload;
            } else {
                // A pasted upload id is accepted in the job_run_id field, as before.
                upload = rid == null ? null : uploads.findById(rid).orElse(null);
                if (upload == null) {
                    throw ApiException.notFound("Job run not found");
                }
            }
        }
        if (upload == null) {
            if (uploadId == null || uploadId.isBlank()) {
                throw ApiException.badRequest("upload_id or job_run_id required");
            }
            UUID id = tryUuid(uploadId);
            upload = id == null ? null : uploads.findById(id).orElse(null);
            if (upload == null) {
                throw ApiException.notFound("Upload not found");
            }
        }
        if (!scope.canSee(user, upload.department)) {
            throw ApiException.notFound("Upload not found");
        }
        if (run != null && !List.of("success", "failed").contains(run.status)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("error", "Report still generating");
            m.put("status", run.status);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(m);
        }

        boolean summaryMode = "transform_gradebook".equalsIgnoreCase(
                upload.processMode == null ? "transform_gradebook" : upload.processMode.trim());
        String prefix = summaryMode ? "summary" : "processed";

        if (pdf) {
            if (upload.reportPdfKey == null || upload.reportPdfKey.isBlank() || !store.exists(upload.reportPdfKey)) {
                throw ApiException.notFound("PDF not available yet");
            }
            return attachment(store.get(upload.reportPdfKey), MediaType.APPLICATION_PDF,
                    prefix + "-" + upload.uploadId + ".pdf");
        }
        if (upload.reportCsvKey != null && !upload.reportCsvKey.isBlank() && store.exists(upload.reportCsvKey)) {
            return attachment(store.get(upload.reportCsvKey), MediaType.parseMediaType("text/csv"),
                    prefix + "-" + upload.uploadId + ".csv");
        }
        // Not generated (yet): small metadata CSV so the download still works.
        String csv = TableIO.toCsv(List.of("Field", "Value"), List.of(
                List.of("Upload ID", upload.uploadId.toString()),
                List.of("Department", upload.department),
                List.of("Filename", upload.filename),
                List.of("Status", upload.status),
                List.of("Received At", upload.receivedAt.toString())));
        return attachment(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8), MediaType.parseMediaType("text/csv"),
                "report-" + upload.uploadId + ".csv");
    }

    private static ResponseEntity<byte[]> attachment(byte[] data, MediaType type, String filename) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"").body(data);
    }

    private static UUID tryUuid(String s) {
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
