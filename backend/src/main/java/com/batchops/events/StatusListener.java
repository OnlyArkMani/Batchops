package com.batchops.events;

import com.batchops.domain.Topics;
import com.batchops.sse.SseHub;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Every API instance gets its own consumer group, so each receives the full status feed. */
@Component
@ConditionalOnProperty(name = "batchops.roles.api", havingValue = "true", matchIfMissing = true)
public class StatusListener {

    private final SseHub hub;

    public StatusListener(SseHub hub) {
        this.hub = hub;
    }

    @KafkaListener(id = "status-feed", topics = Topics.STATUS,
            groupId = "sse-${batchops.instance-id:local}",
            properties = {"auto.offset.reset=latest"})
    public void onStatus(ConsumerRecord<String, String> rec) {
        hub.broadcast("upload", rec.value());
    }
}
