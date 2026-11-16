package com.reviewpulse.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reviewpulse.observability.MetricsSummaryService;

/** Dashboard-friendly metrics. Full Micrometer metrics are at /actuator/prometheus. */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MetricsSummaryService summary;

    public MetricsController(MetricsSummaryService summary) {
        this.summary = summary;
    }

    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> summary() {
        return ResponseEntity.ok(ApiResponse.ok(summary.summary()));
    }
}
