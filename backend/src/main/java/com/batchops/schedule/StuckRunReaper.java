package com.batchops.schedule;

import com.batchops.config.AppProperties;
import com.batchops.domain.UploadStatus;
import com.batchops.incident.IncidentEngine;
import com.batchops.persistence.JobRun;
import com.batchops.persistence.JobRunRepository;
import com.batchops.persistence.Upload;
import com.batchops.pipeline.JobRunService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Safety net for runs that never finished (process killed mid-run, message lost). Such runs are
 * failed explicitly and, for uploads, surfaced as an incident instead of sitting in "running" forever.
 */
@Component
public class StuckRunReaper {

    private static final Logger log = LoggerFactory.getLogger(StuckRunReaper.class);

    private final JobRunRepository runs;
    private final JobRunService runService;
    private final IncidentEngine incidents;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public StuckRunReaper(JobRunRepository runs, JobRunService runService, IncidentEngine incidents,
                          TransactionTemplate tx, AppProperties props) {
        this.runs = runs;
        this.runService = runService;
        this.incidents = incidents;
        this.tx = tx;
        this.props = props;
    }

    @Scheduled(fixedDelay = 120_000L, initialDelay = 90_000L)
    public void reap() {
        if (!props.roles().scheduler()) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofMinutes(props.pipeline().stuckRunMinutes()));
        try {
            tx.executeWithoutResult(s -> {
                List<JobRun> stale = new ArrayList<>();
                for (String status : List.of("running", "retrying", "queued")) {
                    stale.addAll(runs.findByStatusAndStartedAtBefore(status, cutoff));
                }
                for (JobRun r : stale) {
                    if (r.finishedAt != null) {
                        continue;
                    }
                    String msg = "Run exceeded " + props.pipeline().stuckRunMinutes()
                            + " minutes without completing and was reaped";
                    runService.finish(r, "failed", msg, 1);
                    Upload u = r.upload;
                    if (u != null && UploadStatus.parse(u.status) == UploadStatus.PROCESSING) {
                        u.status = UploadStatus.FAILED.wire();
                        incidents.openOrBump(u, r, "pipeline", msg);
                    }
                    log.warn("Reaped stuck run {}", r.runId);
                }
            });
        } catch (Exception e) {
            log.warn("Reaper pass failed: {}", e.toString());
        }
    }
}
