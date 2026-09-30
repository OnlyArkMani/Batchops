package com.batchops.ops;

import com.batchops.persistence.DeadLetterRepository;
import com.batchops.persistence.OutboxRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Thin facade over Micrometer so call sites stay readable and metric names stay consistent. */
@Component
public class Metrics {

    private final MeterRegistry registry;

    public Metrics(MeterRegistry registry, OutboxRepository outbox, DeadLetterRepository dlq) {
        this.registry = registry;
        Gauge.builder("batchops.outbox.backlog", outbox, r -> safe(r::countByPublishedAtIsNull))
                .description("Outbox events waiting to be published").register(registry);
        Gauge.builder("batchops.dlq.pending", dlq, r -> safe(r::countByReplayedAtIsNull))
                .description("Dead-lettered events waiting for replay").register(registry);
    }

    private static double safe(java.util.function.LongSupplier s) {
        try {
            return s.getAsLong();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    public void jobRun(String job, String status, long durationMs) {
        Counter.builder("batchops.job.runs").description("Job runs by job and status")
                .tag("job", job == null ? "unknown" : job).tag("status", status).register(registry).increment();
        if (durationMs > 0) {
            Timer.builder("batchops.job.duration").tag("job", job == null ? "unknown" : job)
                    .register(registry).record(Duration.ofMillis(durationMs));
        }
    }

    public void incident(String state) {
        Counter.builder("batchops.incidents").description("Incidents by state")
                .tag("state", state).register(registry).increment();
    }

    public void outboxPublished(int n) {
        if (n > 0) {
            Counter.builder("batchops.outbox.published").register(registry).increment(n);
        }
    }

    public void stage(String stage, String outcome, long nanos) {
        Timer.builder("batchops.stage.duration").description("Stage processing time")
                .tag("stage", stage).tag("outcome", outcome).register(registry)
                .record(Duration.ofNanos(nanos));
    }

    public void retryScheduled(String tier) {
        Counter.builder("batchops.retry.scheduled").tag("tier", tier).register(registry).increment();
    }

    public void deadLettered(String stage) {
        Counter.builder("batchops.dlq.added").tag("stage", stage).register(registry).increment();
    }

    public void duplicateSkipped(String stage) {
        Counter.builder("batchops.events.duplicates").tag("stage", stage).register(registry).increment();
    }
}
