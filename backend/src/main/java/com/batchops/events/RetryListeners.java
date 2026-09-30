package com.batchops.events;

import com.batchops.domain.Topics;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delay tiers. A message waits on its tier topic until its not-before time (the consumer nacks with a
 * sleep, which pauses the partition without triggering a rebalance), then goes back to the stage
 * topic it came from. Non-blocking for the main topics: one slow retry never stalls fresh uploads.
 */
@Component
@ConditionalOnProperty(name = "batchops.roles.worker", havingValue = "true", matchIfMissing = true)
public class RetryListeners {

    private static final Logger log = LoggerFactory.getLogger(RetryListeners.class);
    private static final long MAX_SLEEP_MS = 60_000L;

    private final OutboxService outbox;
    private final TransactionTemplate tx;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    public RetryListeners(OutboxService outbox, TransactionTemplate tx,
                          com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.outbox = outbox;
        this.tx = tx;
        this.mapper = mapper;
    }

    @KafkaListener(id = "retry-5s", topics = Topics.RETRY_5S, groupId = "batchops-retry-5s",
            containerFactory = "retryListenerContainerFactory")
    public void retry5s(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        forwardWhenDue(rec, ack);
    }

    @KafkaListener(id = "retry-1m", topics = Topics.RETRY_1M, groupId = "batchops-retry-1m",
            containerFactory = "retryListenerContainerFactory")
    public void retry1m(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        forwardWhenDue(rec, ack);
    }

    @KafkaListener(id = "retry-10m", topics = Topics.RETRY_10M, groupId = "batchops-retry-10m",
            containerFactory = "retryListenerContainerFactory")
    public void retry10m(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        forwardWhenDue(rec, ack);
    }

    private void forwardWhenDue(ConsumerRecord<String, String> rec, Acknowledgment ack) {
        long notBefore = parseLong(StageMessage.header(rec, EventHeaders.NOT_BEFORE));
        long wait = notBefore - System.currentTimeMillis();
        if (wait > 250) {
            ack.nack(Duration.ofMillis(Math.min(wait, MAX_SLEEP_MS)));
            return;
        }
        String origin = StageMessage.header(rec, EventHeaders.ORIGIN_TOPIC);
        if (origin == null) {
            log.error("Retry record without origin-topic on {}@{}; dropping", rec.topic(), rec.offset());
            ack.acknowledge();
            return;
        }
        StageMessage msg = StageMessage.from(rec, mapper);
        tx.executeWithoutResult(s -> {
            Map<String, String> h = new LinkedHashMap<>();
            h.put(EventHeaders.ATTEMPT, Integer.toString(msg.attempt()));
            outbox.publish(origin, rec.key(), msg.payload(), h);
        });
        ack.acknowledge();
    }

    private static long parseLong(String v) {
        try {
            return v == null ? 0L : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
