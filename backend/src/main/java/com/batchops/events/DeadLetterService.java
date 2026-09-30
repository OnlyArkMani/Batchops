package com.batchops.events;

import com.batchops.domain.Stage;
import com.batchops.domain.Topics;
import com.batchops.incident.IncidentEngine;
import com.batchops.ops.Metrics;
import com.batchops.persistence.DeadLetter;
import com.batchops.persistence.DeadLetterRepository;
import com.batchops.persistence.Upload;
import com.batchops.persistence.UploadRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Parks messages that exhausted every retry tier and lets an operator replay them. */
@Service
public class DeadLetterService {

    private final DeadLetterRepository repo;
    private final UploadRepository uploads;
    private final IncidentEngine engine;
    private final Metrics metrics;

    public DeadLetterService(DeadLetterRepository repo, UploadRepository uploads, IncidentEngine engine,
                             Metrics metrics) {
        this.repo = repo;
        this.uploads = uploads;
        this.engine = engine;
        this.metrics = metrics;
    }

    @Transactional
    public DeadLetter park(Stage stage, StageMessage msg, Throwable error, int attempts) {
        DeadLetter d = new DeadLetter();
        d.eventId = msg.eventId();
        d.uploadId = msg.uploadId();
        d.stage = stage.stageName();
        d.topic = stage.inputTopic();
        d.msgKey = msg.uploadId().toString();
        d.payload = msg.payload();
        d.errorClass = error.getClass().getName();
        d.error = error.getMessage();
        d.attempts = attempts;
        metrics.deadLettered(stage.stageName());
        return repo.save(d);
    }

    @Transactional(readOnly = true)
    public List<DeadLetter> pending() {
        return repo.findByReplayedAtIsNullOrderByCreatedAtAsc();
    }

    /** Re-runs the whole pipeline for the upload on a fresh version. */
    @Transactional
    public boolean replay(long id) {
        Optional<DeadLetter> found = repo.findById(id);
        if (found.isEmpty() || found.get().replayedAt != null) {
            return false;
        }
        DeadLetter d = found.get();
        Optional<Upload> up = d.uploadId == null ? Optional.empty() : uploads.findById(d.uploadId);
        if (up.isEmpty()) {
            d.replayedAt = Instant.now();
            return false;
        }
        engine.requeueNow(up.get());
        d.replayedAt = Instant.now();
        repo.save(d);
        return true;
    }

    @Transactional
    public int replayAll() {
        int n = 0;
        for (DeadLetter d : repo.findByReplayedAtIsNullOrderByCreatedAtAsc()) {
            if (replay(d.id)) {
                n++;
            }
        }
        return n;
    }

    public static String dlqTopic() {
        return Topics.DLQ;
    }
}
