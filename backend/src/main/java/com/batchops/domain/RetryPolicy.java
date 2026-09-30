package com.batchops.domain;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.sql.SQLTransientException;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

/**
 * One uniform retry policy replacing the original ad-hoc auto_retry configs.
 *
 * <p>Attempt 1 is the first execution. Failure number n (transient) is retried through tier n,
 * so three tiers mean at most four executions before the message lands in the dead-letter queue.
 */
public final class RetryPolicy {

    public record Tier(String name, Duration delay) {
        public String topic() {
            return Topics.RETRY_PREFIX + name;
        }
    }

    public static final List<Tier> TIERS = List.of(
            new Tier("5s", Duration.ofSeconds(5)),
            new Tier("1m", Duration.ofMinutes(1)),
            new Tier("10m", Duration.ofMinutes(10)));

    private RetryPolicy() {
    }

    public static Optional<Tier> tierAfterFailure(int attempt) {
        if (attempt < 1 || attempt > TIERS.size()) {
            return Optional.empty();
        }
        return Optional.of(TIERS.get(attempt - 1));
    }

    public static StageException.Kind classify(Throwable error) {
        Throwable t = error;
        int guard = 0;
        while (t != null && guard++ < 12) {
            if (t instanceof StageException se) {
                return se.kind();
            }
            if (t instanceof FileNotFoundException) {
                return StageException.Kind.PERMANENT;
            }
            if (t instanceof IllegalArgumentException) {
                return StageException.Kind.PERMANENT;
            }
            if (t instanceof TimeoutException || t instanceof SQLTransientException) {
                return StageException.Kind.TRANSIENT;
            }
            if (t instanceof IOException) {
                return StageException.Kind.TRANSIENT;
            }
            String msg = t.getMessage() == null ? "" : t.getMessage().toLowerCase(Locale.ROOT);
            if (msg.contains("temporarily unavailable") || msg.contains("share violation")
                    || msg.contains("connection reset") || msg.contains("timed out")) {
                return StageException.Kind.TRANSIENT;
            }
            t = t.getCause() == t ? null : t.getCause();
        }
        // Unknown failures are retried a bounded number of times, then dead-lettered for humans.
        return StageException.Kind.TRANSIENT;
    }
}
