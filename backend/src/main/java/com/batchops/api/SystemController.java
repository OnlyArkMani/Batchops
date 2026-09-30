package com.batchops.api;

import com.batchops.ops.HealthService;
import com.batchops.persistence.IncidentRepository;
import com.batchops.persistence.JobRunRepository;
import com.batchops.persistence.TicketRepository;
import com.batchops.persistence.UploadRepository;
import com.batchops.security.Access;
import com.batchops.sse.SseHub;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
public class SystemController {

    private final HealthService health;
    private final UploadRepository uploads;
    private final JobRunRepository runs;
    private final IncidentRepository incidents;
    private final TicketRepository tickets;
    private final SseHub hub;

    public SystemController(HealthService health, UploadRepository uploads, JobRunRepository runs,
                            IncidentRepository incidents, TicketRepository tickets, SseHub hub) {
        this.health = health;
        this.uploads = uploads;
        this.runs = runs;
        this.incidents = incidents;
        this.tickets = tickets;
        this.hub = hub;
    }

    @GetMapping("/")
    public String index() {
        return "BatchOps (Spring Boot) backend is running. Use the React UI (Vite dev server) on port 5173, "
                + "or call the API under /api/.";
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return health.check();
    }

    @GetMapping({"/api/dashboard-metrics", "/api/dashboard/metrics"})
    public Map<String, Object> dashboard() {
        Access.current();
        Instant from = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = from.plus(Duration.ofDays(1));
        Map<String, Object> kpis = new LinkedHashMap<>();
        kpis.put("todays_uploads", uploads.countByReceivedAtGreaterThanEqualAndReceivedAtLessThan(from, to));
        kpis.put("todays_runs", runs.countActiveBetween(from, to));
        kpis.put("open_incidents", incidents.countByStateIn(List.of("open", "in_progress")));
        kpis.put("open_tickets", tickets.countByStatusIn(List.of("open", "in_progress")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kpis", kpis);
        return out;
    }

    /** Live pipeline status (Server-Sent Events). Browsers pass ?token= because EventSource has no headers. */
    @GetMapping(value = "/api/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        Access.current();
        return hub.subscribe();
    }
}
