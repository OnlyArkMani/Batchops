package com.batchops.ops;

import com.batchops.persistence.DeadLetterRepository;
import com.batchops.persistence.OutboxRepository;
import com.batchops.storage.ObjectStore;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Service;

/** Dependency health for /api/health/. Values contain "Healthy" or "Unhealthy" (the UI keys off that). */
@Service
public class HealthService {

    private final JdbcTemplate jdbc;
    private final KafkaAdmin kafkaAdmin;
    private final ObjectStore store;
    private final OutboxRepository outbox;
    private final DeadLetterRepository dlq;

    private volatile long kafkaCheckedAt;
    private volatile String kafkaStatus = "Unknown";

    public HealthService(JdbcTemplate jdbc, KafkaAdmin kafkaAdmin, ObjectStore store, OutboxRepository outbox,
                         DeadLetterRepository dlq) {
        this.jdbc = jdbc;
        this.kafkaAdmin = kafkaAdmin;
        this.store = store;
        this.outbox = outbox;
        this.dlq = dlq;
    }

    public Map<String, String> check() {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("api", "Healthy");
        try {
            jdbc.queryForObject("select 1", Integer.class);
            h.put("postgres", "Healthy");
        } catch (Exception e) {
            h.put("postgres", "Unhealthy (" + e.getClass().getSimpleName() + ")");
        }
        h.put("kafka", kafka());
        try {
            store.exists("healthcheck/.probe");
            h.put("object_storage", "Healthy");
        } catch (Exception e) {
            h.put("object_storage", "Unhealthy (" + e.getClass().getSimpleName() + ")");
        }
        try {
            long backlog = outbox.countByPublishedAtIsNull();
            h.put("outbox", backlog > 500 ? "Unhealthy (" + backlog + " unpublished)" : "Healthy (" + backlog + " pending)");
            long dead = dlq.countByReplayedAtIsNull();
            h.put("dead_letters", dead > 0 ? "Unhealthy (" + dead + " awaiting replay)" : "Healthy (0 pending)");
        } catch (Exception e) {
            h.put("outbox", "Unknown (" + e.getClass().getSimpleName() + ")");
        }
        return h;
    }

    private String kafka() {
        long now = System.currentTimeMillis();
        if (now - kafkaCheckedAt < 10_000L) {
            return kafkaStatus;
        }
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            int nodes = admin.describeCluster().nodes().get(2, TimeUnit.SECONDS).size();
            kafkaStatus = "Healthy (" + nodes + " broker" + (nodes == 1 ? "" : "s") + ")";
        } catch (Exception e) {
            kafkaStatus = "Unhealthy (" + e.getClass().getSimpleName() + ")";
        }
        kafkaCheckedAt = now;
        return kafkaStatus;
    }

    static Duration unused() {
        return Duration.ZERO;
    }
}
