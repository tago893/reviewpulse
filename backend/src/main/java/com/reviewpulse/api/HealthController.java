package com.reviewpulse.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregated health for humans and the UI. Returns 503 when any required component is down, so a
 * load balancer or script never sees "healthy" while search would be wrong.
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final JdbcClient jdbc;

    public HealthController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> health() {
        Map<String, Object> components = new LinkedHashMap<>();
        boolean dbUp = checkDatabase(components);
        String overall = dbUp ? "UP" : "DOWN";
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", overall);
        body.put("components", components);
        HttpStatus status = dbUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
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
}
