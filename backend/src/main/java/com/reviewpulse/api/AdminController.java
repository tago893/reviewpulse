package com.reviewpulse.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.reviewpulse.events.IndexerWorker;
import com.reviewpulse.observability.IndexerProgress;
import com.reviewpulse.outbox.OutboxPublisher;
import com.reviewpulse.outbox.OutboxRepository;
import com.reviewpulse.reviews.ReviewRepository;
import com.reviewpulse.search.IndexStatus;
import com.reviewpulse.search.index.IndexStats;
import com.reviewpulse.search.index.SearchIndex;

/** Operator endpoints. Protected by {@link AdminTokenFilter}. */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final IndexerWorker indexer;
    private final IndexStatus status;
    private final SearchIndex index;
    private final ReviewRepository reviews;
    private final IndexerProgress progress;
    private final OutboxRepository outbox;
    private final OutboxPublisher publisher;

    public AdminController(IndexerWorker indexer, IndexStatus status, SearchIndex index, ReviewRepository reviews,
                           IndexerProgress progress, OutboxRepository outbox, OutboxPublisher publisher) {
        this.indexer = indexer;
        this.status = status;
        this.index = index;
        this.reviews = reviews;
        this.progress = progress;
        this.outbox = outbox;
        this.publisher = publisher;
    }

    @PostMapping("/reindex")
    public ResponseEntity<ApiResponse<Map<String, Object>>> reindex() {
        if (!indexer.requestRebuild("admin request")) {
            throw new ConflictException("REBUILD_IN_PROGRESS", "a rebuild is already pending or running");
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(Map.of("rebuild", "queued")));
    }

    @GetMapping("/index-status")
    public ResponseEntity<ApiResponse<Map<String, Object>>> indexStatus() {
        IndexStatus.Snapshot s = status.snapshot();
        IndexStats stats = index.stats();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", s.state().name());
        body.put("detail", s.detail());
        body.put("since", s.since());
        body.put("rebuilding", indexer.isRebuilding());
        body.put("lastBuildCompletedAt", s.lastBuildCompletedAt());
        body.put("lastBuildMillis", s.lastBuildMillis());
        body.put("generation", index.generation());
        body.put("compactions", index.compactions());
        body.put("liveDocs", stats.liveDocs());
        body.put("docSlots", stats.docSlots());
        body.put("deadDocs", stats.deadDocs());
        body.put("terms", stats.terms());
        body.put("postings", stats.postings());
        body.put("products", stats.products());
        body.put("trackedReviews", stats.trackedReviews());
        body.put("approximateBytes", stats.approximateBytes());
        body.put("databaseLiveReviews", reviews.countLive());
        body.put("indexer", progress.view());
        Map<String, Object> outboxView = new LinkedHashMap<>();
        outboxView.put("pending", outbox.countByStatus("PENDING"));
        outboxView.put("failed", outbox.countByStatus("FAILED"));
        outboxView.put("oldestPendingMillis", outbox.oldestPendingAge().toMillis());
        outboxView.put("backoffMillis", publisher.currentBackoffMillis());
        outboxView.put("lastSuccessAt", publisher.lastSuccessAt());
        outboxView.put("lastError", publisher.lastError());
        body.put("outbox", outboxView);
        return ResponseEntity.ok(ApiResponse.ok(body));
    }
}
