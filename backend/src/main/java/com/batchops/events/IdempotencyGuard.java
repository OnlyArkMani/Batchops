package com.batchops.events;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Exactly-once <em>effect</em> on top of at-least-once delivery: the (consumer, event_id) row is
 * inserted in the same transaction as the stage's side effects, so a redelivery becomes a no-op.
 */
@Component
public class IdempotencyGuard {

    private final JdbcTemplate jdbc;

    public IdempotencyGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** @return true if this is the first time the consumer sees the event. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean firstTime(String consumer, UUID eventId) {
        int rows = jdbc.update(
                "insert into processed_events(consumer, event_id) values (?, ?) on conflict do nothing",
                consumer, eventId);
        return rows == 1;
    }

    /** Housekeeping for events older than a week. */
    public int purgeOld() {
        return jdbc.update("delete from processed_events where processed_at < now() - interval '7 days'");
    }
}
