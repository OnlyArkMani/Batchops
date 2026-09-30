package com.batchops.ops;

import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Prometheus text exposition at /api/metrics (same path the original Django app exposed). */
@RestController
public class MetricsController {

    private final PrometheusMeterRegistry registry;

    public MetricsController(PrometheusMeterRegistry registry) {
        this.registry = registry;
    }

    @GetMapping(value = "/api/metrics", produces = "text/plain;version=0.0.4;charset=utf-8")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/plain;version=0.0.4;charset=utf-8"))
                .body(registry.scrape());
    }
}
