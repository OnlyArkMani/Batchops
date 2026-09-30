package com.batchops.events;

import com.batchops.config.AppProperties;
import com.batchops.ops.Metrics;
import com.batchops.persistence.OutboxEvent;
import com.batchops.persistence.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains the outbox into Kafka. Rows are claimed with FOR UPDATE SKIP LOCKED so several relays can
 * run side by side; publication is at-least-once and consumers de-duplicate on event-id.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repo;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final AppProperties props;
    private final Metrics metrics;

    public OutboxRelay(OutboxRepository repo, KafkaTemplate<String, String> kafka, ObjectMapper mapper,
                       TransactionTemplate tx, AppProperties props, Metrics metrics) {
        this.repo = repo;
        this.kafka = kafka;
        this.mapper = mapper;
        this.tx = tx;
        this.props = props;
        this.metrics = metrics;
    }

    @Scheduled(fixedDelayString = "${batchops.kafka.outbox-poll-ms:500}")
    public void drain() {
        if (!props.roles().worker() && !props.roles().api()) {
            return;
        }
        try {
            int sent;
            do {
                Integer n = tx.execute(status -> relayBatch());
                sent = n == null ? 0 : n;
            } while (sent >= props.kafka().outboxBatch());
        } catch (Exception e) {
            log.warn("Outbox relay pass failed, will retry: {}", e.toString());
        }
    }

    private int relayBatch() {
        List<OutboxEvent> batch = repo.lockNextBatch(props.kafka().outboxBatch());
        int sent = 0;
        for (OutboxEvent e : batch) {
            try {
                String body = mapper.writeValueAsString(e.payload);
                ProducerRecord<String, String> rec = new ProducerRecord<>(e.topic, e.msgKey, body);
                for (Map.Entry<String, String> h : e.headers.entrySet()) {
                    rec.headers().add(h.getKey(), h.getValue().getBytes(StandardCharsets.UTF_8));
                }
                kafka.send(rec).get(10, TimeUnit.SECONDS);
                e.publishedAt = Instant.now();
                sent++;
            } catch (Exception ex) {
                // Stop at the first failure so per-key ordering is preserved; the rest stay unpublished.
                log.warn("Outbox event {} to {} not published yet: {}", e.eventId, e.topic, ex.toString());
                break;
            }
        }
        metrics.outboxPublished(sent);
        return sent;
    }

    /** Housekeeping: published rows older than a day are deleted. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    public void cleanup() {
        try {
            tx.executeWithoutResult(s -> repo.deletePublishedBefore(Instant.now().minus(Duration.ofDays(1))));
        } catch (Exception e) {
            log.debug("Outbox cleanup skipped: {}", e.toString());
        }
    }
}
