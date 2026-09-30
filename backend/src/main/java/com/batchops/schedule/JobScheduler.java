package com.batchops.schedule;

import com.batchops.config.AppProperties;
import com.batchops.domain.CronSupport;
import com.batchops.persistence.Job;
import com.batchops.persistence.JobRepository;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * Database-reconciling scheduler. Every tick it reads the jobs table, so edits take effect without a
 * restart (the old design only registered a cron on save, and lost it whenever Redis was flushed).
 * Replicas coordinate through a unique insert into job_fires: whoever inserts (job, fire_time) first
 * runs it, everyone else skips. Fires missed while the service was down are caught up within a
 * bounded window instead of being silently skipped forever.
 */
@Component
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);
    private static final int CATCH_UP_MINUTES = 10;
    private static final int MAX_FIRES_PER_TICK = 20;

    private final JobRepository jobs;
    private final JdbcTemplate jdbc;
    private final JobExecutor executor;
    private final AppProperties props;

    public JobScheduler(JobRepository jobs, JdbcTemplate jdbc, JobExecutor executor, AppProperties props) {
        this.jobs = jobs;
        this.jdbc = jdbc;
        this.executor = executor;
        this.props = props;
    }

    @Scheduled(fixedDelay = 15_000L, initialDelay = 20_000L)
    public void tick() {
        if (!props.roles().scheduler()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        for (Job job : jobs.findByScheduleCronIsNotNull()) {
            String cron = job.scheduleCron == null ? "" : job.scheduleCron.trim();
            if (cron.isEmpty()) {
                continue;
            }
            try {
                fireDue(job, CronExpression.parse(CronSupport.toSpring(cron)), now);
            } catch (IllegalArgumentException e) {
                log.warn("Job {} has an invalid cron '{}': {}", job.name, cron, e.getMessage());
            } catch (Exception e) {
                log.warn("Scheduler pass for job {} failed: {}", job.name, e.toString());
            }
        }
    }

    private void fireDue(Job job, CronExpression expr, ZonedDateTime now) {
        ZonedDateTime cursor = now.minus(CATCH_UP_MINUTES, ChronoUnit.MINUTES);
        ZonedDateTime fire = expr.next(cursor);
        int guard = 0;
        while (fire != null && !fire.isAfter(now) && guard++ < MAX_FIRES_PER_TICK) {
            int inserted = jdbc.update(
                    "insert into job_fires(job_id, fire_time, fired_by) values (?, ?, ?) on conflict do nothing",
                    job.id, java.sql.Timestamp.from(fire.toInstant()), props.instanceId());
            if (inserted == 1) {
                log.info("Firing job {} for {}", job.name, fire);
                executor.runNow(job, null);
            }
            fire = expr.next(fire);
        }
    }
}
