package com.batchops.pipeline;

import com.batchops.domain.RetryPolicy;
import com.batchops.domain.Stage;
import com.batchops.domain.StageException;
import com.batchops.domain.Topics;
import com.batchops.domain.UploadStatus;
import com.batchops.events.DeadLetterService;
import com.batchops.events.EventHeaders;
import com.batchops.events.IdempotencyGuard;
import com.batchops.events.OutboxService;
import com.batchops.events.StageMessage;
import com.batchops.incident.IncidentEngine;
import com.batchops.ops.Metrics;
import com.batchops.persistence.JobRun;
import com.batchops.persistence.Upload;
import com.batchops.persistence.UploadRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Executes one stage for one Kafka message.
 *
 * <p>Happy path, all in a single database transaction: idempotency check, stage work, upload update,
 * and the outbox rows for the next stage and the status feed. Failure path, in a fresh transaction:
 * classify, then either schedule a retry tier, or fail the upload, open an incident and (when the
 * failure was transient and retries ran out) dead-letter the message.
 */
@Service
public class PipelineService {

    private static final Logger log = LoggerFactory.getLogger(PipelineService.class);

    private final TransactionTemplate tx;
    private final UploadRepository uploads;
    private final StageProcessor processor;
    private final JobRunService runs;
    private final IdempotencyGuard guard;
    private final OutboxService outbox;
    private final IncidentEngine incidents;
    private final DeadLetterService deadLetters;
    private final Metrics metrics;
    private final ObjectMapper mapper;

    public PipelineService(TransactionTemplate tx, UploadRepository uploads, StageProcessor processor,
                           JobRunService runs, IdempotencyGuard guard, OutboxService outbox,
                           IncidentEngine incidents, DeadLetterService deadLetters, Metrics metrics,
                           ObjectMapper mapper) {
        this.tx = tx;
        this.uploads = uploads;
        this.processor = processor;
        this.runs = runs;
        this.guard = guard;
        this.outbox = outbox;
        this.incidents = incidents;
        this.deadLetters = deadLetters;
        this.metrics = metrics;
        this.mapper = mapper;
    }

    public void handle(Stage stage, ConsumerRecord<String, String> record) {
        StageMessage msg;
        try {
            msg = StageMessage.from(record, mapper);
        } catch (IllegalArgumentException e) {
            log.error("Dropping malformed message on {}: {}", record.topic(), e.getMessage());
            return; // unparseable input can never succeed; the record is acknowledged and logged
        }
        long started = System.nanoTime();
        try {
            Boolean processed = tx.execute(status -> runInTransaction(stage, msg));
            metrics.stage(stage.stageName(), Boolean.TRUE.equals(processed) ? "ok" : "skipped",
                    System.nanoTime() - started);
        } catch (Throwable ex) {
            metrics.stage(stage.stageName(), "error", System.nanoTime() - started);
            tx.executeWithoutResult(status -> handleFailure(stage, msg, ex));
        }
    }

    private boolean runInTransaction(Stage stage, StageMessage msg) {
        if (!guard.firstTime(stage.stageName(), msg.eventId())) {
            metrics.duplicateSkipped(stage.stageName());
            return false;
        }
        Optional<Upload> found = uploads.findById(msg.uploadId());
        if (found.isEmpty()) {
            log.warn("Upload {} no longer exists; dropping {} message", msg.uploadId(), stage.stageName());
            return false;
        }
        Upload u = found.get();
        if (msg.version() < u.version) {
            log.info("Skipping stale {} message for upload {} (v{} < v{})", stage.stageName(), u.uploadId,
                    msg.version(), u.version);
            return false;
        }
        if (stage == Stage.STANDARDIZER) {
            UploadStatus cur = UploadStatus.parse(u.status);
            if (cur.canMoveTo(UploadStatus.PROCESSING)) {
                u.status = UploadStatus.PROCESSING.wire();
            }
        }
        JobRun run = runs.pipelineRun(u);
        try {
            processor.execute(stage, u, run);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // Checked failures (I/O against the object store): FileNotFound is permanent, the rest transient.
            throw new StageException(RetryPolicy.classify(e), e.getMessage(), e);
        }

        if (stage == Stage.PUBLISHER) {
            u.status = UploadStatus.parse(u.status).moveTo(UploadStatus.PUBLISHED).wire();
            runs.finish(run, "success", "Pipeline completed", 0);
            incidents.autoResolveAfterSuccess(u);
        }
        uploads.save(u);
        outbox.publish(stage.outputTopic(), u.uploadId, msg.version(), 1);
        outbox.publish(Topics.STATUS, u.uploadId.toString(),
                statusPayload(u, stage.stageName(), u.status, null), new LinkedHashMap<>());
        return true;
    }

    private void handleFailure(Stage stage, StageMessage msg, Throwable ex) {
        Optional<Upload> found = uploads.findById(msg.uploadId());
        if (found.isEmpty()) {
            log.warn("Failure for vanished upload {}: {}", msg.uploadId(), ex.toString());
            return;
        }
        Upload u = found.get();
        if (msg.version() < u.version) {
            return; // a newer attempt already superseded this one
        }
        StageException.Kind kind = RetryPolicy.classify(ex);
        String error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        JobRun run = runs.pipelineRun(u);

        Optional<RetryPolicy.Tier> tier = kind == StageException.Kind.TRANSIENT
                ? RetryPolicy.tierAfterFailure(msg.attempt()) : Optional.empty();
        if (tier.isPresent()) {
            runs.markRetrying(run, msg.attempt(), error);
            Map<String, String> h = new LinkedHashMap<>();
            h.put(EventHeaders.ATTEMPT, Integer.toString(msg.attempt() + 1));
            h.put(EventHeaders.ORIGIN_TOPIC, stage.inputTopic());
            h.put(EventHeaders.NOT_BEFORE, Long.toString(Instant.now().plus(tier.get().delay()).toEpochMilli()));
            h.put(EventHeaders.LAST_ERROR, com.batchops.incident.Timelines.trunc(error, 200));
            outbox.publish(tier.get().topic(), u.uploadId.toString(),
                    StageMessage.payloadOf(u.uploadId, msg.version()), h);
            outbox.publish(Topics.STATUS, u.uploadId.toString(),
                    statusPayload(u, stage.stageName(), "retrying", error), new LinkedHashMap<>());
            metrics.retryScheduled(tier.get().name());
            log.info("Stage {} for upload {} failed transiently ({}); retry via {}", stage.stageName(), u.uploadId,
                    error, tier.get().topic());
            return;
        }

        // Terminal failure.
        if (ex instanceof ValidationFailedException vf) {
            u.validationReport = vf.report();
        }
        UploadStatus cur = UploadStatus.parse(u.status);
        if (cur.canMoveTo(UploadStatus.FAILED)) {
            u.status = UploadStatus.FAILED.wire();
        }
        uploads.save(u);
        runs.finish(run, "failed", "Stage " + stage.stageName() + " failed: " + error, 1);
        if (kind == StageException.Kind.TRANSIENT) {
            deadLetters.park(stage, msg, ex, msg.attempt());
        }
        String step = stage.steps().get(0);
        incidents.openOrBump(u, run, step, error);
        outbox.publish(Topics.FAILED, u.uploadId.toString(), statusPayload(u, stage.stageName(), "failed", error),
                new LinkedHashMap<>());
        outbox.publish(Topics.STATUS, u.uploadId.toString(), statusPayload(u, stage.stageName(), u.status, error),
                new LinkedHashMap<>());
    }

    static Map<String, Object> statusPayload(Upload u, String stage, String status, String message) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("upload_id", u.uploadId.toString());
        p.put("filename", u.filename);
        p.put("department", u.department);
        p.put("status", status);
        p.put("stage", stage);
        p.put("version", u.version);
        p.put("message", message);
        p.put("at", Instant.now().toString());
        return p;
    }
}
