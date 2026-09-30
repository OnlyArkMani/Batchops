package com.batchops.pipeline;

import com.batchops.api.ApiException;
import com.batchops.domain.Topics;
import com.batchops.incident.IncidentEngine;
import com.batchops.events.OutboxService;
import com.batchops.persistence.Upload;
import com.batchops.persistence.UploadRepository;
import com.batchops.storage.ObjectStore;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Accepts a file: stores the immutable source, records the upload and emits uploads.received. */
@Service
public class UploadService {

    public record Result(Upload upload, boolean created) {
    }

    private final UploadRepository uploads;
    private final ObjectStore store;
    private final OutboxService outbox;
    private final IncidentEngine engine;

    public UploadService(UploadRepository uploads, ObjectStore store, OutboxService outbox, IncidentEngine engine) {
        this.uploads = uploads;
        this.store = store;
        this.outbox = outbox;
        this.engine = engine;
    }

    /** Re-runs the pipeline on a fresh version; the source file itself is never modified. */
    @Transactional
    public Upload retry(java.util.UUID id) {
        Upload u = uploads.findById(id).orElseThrow(() -> ApiException.notFound("Upload not found"));
        engine.requeueNow(u);
        return u;
    }

    @Transactional
    public Upload update(java.util.UUID id, Map<String, Object> body) {
        Upload u = uploads.findById(id).orElseThrow(() -> ApiException.notFound("Upload not found"));
        if (body.get("notes") != null) {
            u.notes = String.valueOf(body.get("notes"));
        }
        if (body.get("department") != null && !String.valueOf(body.get("department")).isBlank()) {
            u.department = String.valueOf(body.get("department")).trim();
        }
        return uploads.save(u);
    }

    @Transactional
    public void delete(java.util.UUID id) {
        Upload u = uploads.findById(id).orElseThrow(() -> ApiException.notFound("Upload not found"));
        uploads.delete(u);
    }

    /**
     * @param dedupe when true an identical file (same department + SHA-256) that is not failed returns
     *               the existing upload instead of processing it twice
     */
    @Transactional
    public Result ingest(byte[] data, String filename, String contentType, String department, String notes,
                         String processMode, Map<String, Object> processConfig, String createdBy, boolean dedupe)
            throws IOException {
        String sha = sha256(data);
        if (dedupe) {
            Optional<Upload> existing = uploads.findFirstByDepartmentAndSha256AndStatusNot(department, sha, "failed");
            if (existing.isPresent()) {
                return new Result(existing.get(), false);
            }
        }
        Upload u = new Upload();
        u.department = department;
        u.filename = filename == null || filename.isBlank() ? "upload" : filename;
        u.mimeType = contentType == null ? "" : contentType;
        u.notes = notes;
        u.status = "processing";
        u.sha256 = dedupe ? sha : null;
        u.sizeBytes = data.length;
        u.processMode = processMode == null || processMode.isBlank() ? "transform_gradebook" : processMode;
        u.processConfig = processConfig == null ? new LinkedHashMap<>() : processConfig;
        u.createdBy = createdBy;
        u.sourceKey = ArtifactKeys.source(u);
        u.currentKey = u.sourceKey;
        u.filePath = u.sourceKey;
        store.put(u.sourceKey, data, u.mimeType.isBlank() ? "application/octet-stream" : u.mimeType);
        uploads.save(u);
        outbox.publish(Topics.RECEIVED, u.uploadId, u.version, 1);
        return new Result(u, true);
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
