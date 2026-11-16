package com.reviewpulse.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reviewpulse.events.IndexCheckpoints;
import com.reviewpulse.observability.IndexerProgress;
import com.reviewpulse.search.IndexState;
import com.reviewpulse.search.IndexStatus;

/**
 * Aggregated health.
 * <ul>
 *   <li>DOWN (HTTP 503): PostgreSQL unreachable or no usable index; search cannot answer correctly.</li>
 *   <li>DEGRADED (HTTP 200): search answers, but something needs attention (Kafka unreachable so live
 *       updates are paused, checkpoints failing, outbox rows parked as FAILED, index rebuilding).</li>
 *   <li>UP: everything nominal.</li>
 * </ul>
 * The index is never reported healthy while it is known to be behind for a reason other than normal lag.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcClient jdbc;
    private final IndexStatus indexStatus;
    private final IndexerProgress progress;
    private final IndexCheckpoints checkpoints;

    public HealthController(JdbcClient jdbc, IndexStatus indexStatus, IndexerProgress progress,
                            IndexCheckpoints checkpoints) {
        this.jdbc = jdbc;
        this.indexStatus = indexStatus;
        this.progress = progress;
        this.checkpoints = checkpoints;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean dbUp = checkDatabase(components);
        long failedOutbox = dbUp ? failedOutboxRows() : 0;

        IndexStatus.Snapshot s = indexStatus.snapshot();
        boolean indexServes = s.state().servesQueries() && s.hasServedIndex();
        components.put("index", Map.of("status", indexServes ? (s.state() == IndexState.LIVE ? "UP" : "DEGRADED") : "DOWN",
                "state", s.state().name(), "detail", s.detail()));
        components.put("kafka", Map.of("status", progress.kafkaReachable() ? "UP" : "DOWN",
                "lagEvents", progress.lagEvents()));
        components.put("checkpoints", Map.of("status", checkpoints.healthy() ? "UP" : "DEGRADED",
                "lastError", String.valueOf(checkpoints.status().lastError())));
        components.put("outbox", Map.of("status", failedOutbox == 0 ? "UP" : "DEGRADED", "failedRows", failedOutbox));

        String overall;
        if (!dbUp || !indexServes) {
            overall = "DOWN";
        } else if (s.state() != IndexState.LIVE || !progress.kafkaReachable() || !checkpoints.healthy() || failedOutbox > 0) {
            overall = "DEGRADED";
        } else {
            overall = "UP";
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", overall);
        body.put("components", components);
        HttpStatus status = "DOWN".equals(overall) ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK;
        return ResponseEntity.status(status).body(ApiResponse.ok(body));
    }

    private boolean checkDatabase(Map<String, Object> components) {
        long start = System.nanoTime();
        try {
            Integer one = jdbc.sql("SELECT 1").query(Integer.class).single();
            long micros = (System.nanoTime() - start) / 1_000;
            components.put("database", Map.of("status", one == 1 ? "UP" : "DOWN", "latencyMicros", micros));
            return one == 1;
        } catch (RuntimeException e) {
            log.warn("Database health check failed: {}", e.getMessage());
            components.put("database", Map.of("status", "DOWN", "error", "database unreachable"));
            return false;
        }
    }

    private long failedOutboxRows() {
        try {
            return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = 'FAILED'").query(Long.class).single();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
