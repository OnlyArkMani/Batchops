package com.batchops.events;

import com.batchops.persistence.OutboxEvent;
import com.batchops.persistence.OutboxRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Transactional outbox. Callers insert the event in the same database transaction as their state
 * change; {@link OutboxRelay} ships it to Kafka afterwards. This removes the dual-write gap where a
 * row is committed but the message is lost (or the reverse).
 */
@Service
public class OutboxService {

    private final OutboxRepository repo;

    public OutboxService(OutboxRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String topic, String key, Map<String, Object> payload, Map<String, String> headers) {
        OutboxEvent e = new OutboxEvent();
        e.topic = topic;
        e.msgKey = key;
        e.payload = payload;
        e.headers = headers == null ? new LinkedHashMap<>() : new LinkedHashMap<>(headers);
        e.headers.put(EventHeaders.EVENT_ID, e.eventId.toString());
        repo.save(e);
        return e.eventId;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID publish(String topic, UUID uploadId, int version, int attempt) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put(EventHeaders.ATTEMPT, Integer.toString(attempt));
        return publish(topic, uploadId.toString(), StageMessage.payloadOf(uploadId, version), h);
    }

    /** Convenience for callers that start their own transaction (controllers, schedulers). */
    @Transactional
    public UUID publishNew(String topic, UUID uploadId, int version) {
        return publish(topic, uploadId, version, 1);
    }
}
