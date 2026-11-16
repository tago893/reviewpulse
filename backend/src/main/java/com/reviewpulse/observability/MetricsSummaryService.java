package com.reviewpulse.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.reviewpulse.caching.SearchResultCache;
import com.reviewpulse.events.IndexCheckpoints;
import com.reviewpulse.outbox.OutboxRepository;
import com.reviewpulse.reviews.ReviewRepository;
import com.reviewpulse.search.IndexStatus;
import com.reviewpulse.search.index.SearchIndex;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

/**
 * Compact, UI-oriented view over the metrics. Database counts are refreshed at most every two seconds
 * so a dashboard polling this endpoint never puts COUNT(*) load on PostgreSQL per request.
 */
@Service
public class MetricsSummaryService {

    private static final int WINDOW_SAMPLES = 10;
    private static final Instant STARTED = Instant.now();

    private final MeterRegistry registry;
    private final IndexerProgress progress;
    private final IndexStatus status;
    private final SearchIndex index;
    private final ReviewRepository reviews;
    private final OutboxRepository outbox;
    private final SearchResultCache cache;
    private final IndexCheckpoints checkpoints;
    private final Deque<long[]> throughputSamples = new ArrayDeque<>();
    private volatile Map<String, Object> dbCounts = Map.of();
    private volatile long dbCountsAt;

    public MetricsSummaryService(MeterRegistry registry, IndexerProgress progress, IndexStatus status, SearchIndex index,
                                 ReviewRepository reviews, OutboxRepository outbox, SearchResultCache cache,
                                 IndexCheckpoints checkpoints) {
        this.registry = registry;
        this.progress = progress;
        this.status = status;
        this.index = index;
        this.reviews = reviews;
        this.outbox = outbox;
        this.cache = cache;
        this.checkpoints = checkpoints;
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    void sampleThroughput() {
        synchronized (throughputSamples) {
            throughputSamples.addLast(new long[] {System.nanoTime(), progress.view().eventsConsumed()});
            while (throughputSamples.size() > WINDOW_SAMPLES) {
                throughputSamples.removeFirst();
            }
        }
    }

    private double eventsPerSecond() {
        synchronized (throughputSamples) {
            if (throughputSamples.size() < 2) {
                return 0.0;
            }
            long[] first = throughputSamples.getFirst();
            long[] last = throughputSamples.getLast();
            double seconds = (last[0] - first[0]) / 1e9;
            return seconds <= 0 ? 0.0 : (last[1] - first[1]) / seconds;
        }
    }

    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        IndexStatus.Snapshot s = status.snapshot();
        IndexerProgress.View p = progress.view();
        out.put("uptimeSeconds", Duration.between(STARTED, Instant.now()).toSeconds());
        Map<String, Object> reviewCounts = new LinkedHashMap<>(databaseCounts());
        reviewCounts.put("indexedLive", index.stats().liveDocs());
        out.put("reviews", reviewCounts);
        out.put("search", timer("reviewpulse.search.latency"));
        out.put("searchIndexOnly", timer("reviewpulse.search.index_latency"));
        Map<String, Object> indexing = new LinkedHashMap<>();
        indexing.put("state", s.state().name());
        indexing.put("detail", s.detail());
        indexing.put("generation", index.generation());
        indexing.put("eventsConsumed", p.eventsConsumed());
        indexing.put("eventsPerSecond", Math.round(eventsPerSecond() * 10) / 10.0);
        indexing.put("kafkaReachable", p.kafkaReachable());
        indexing.put("lagEvents", p.lagEvents());
        indexing.put("delayP50Millis", p.delayP50Millis());
        indexing.put("delayP95Millis", p.delayP95Millis());
        indexing.put("delayP99Millis", p.delayP99Millis());
        indexing.put("lastDelayMillis", p.lastDelayMillis());
        indexing.put("deadLettered", p.deadLettered());
        indexing.put("outcomes", p.outcomes());
        indexing.put("lastCheckpointAt", checkpoints.status().lastSavedAt());
        out.put("indexing", indexing);
        SearchResultCache.Stats c = cache.stats();
        out.put("cache", Map.of("enabled", c.enabled(), "hits", c.hits(), "misses", c.misses(), "errors", c.errors(),
                "bypassed", c.bypassed(), "hitRate", Math.round(c.hitRate() * 1000) / 1000.0, "circuitOpen", c.circuitOpen()));
        return out;
    }

    private Map<String, Object> databaseCounts() {
        long now = System.currentTimeMillis();
        if (now - dbCountsAt > 2_000) {
            try {
                Map<String, Object> counts = new LinkedHashMap<>();
                counts.put("databaseLive", reviews.countLive());
                counts.put("outboxPending", outbox.countByStatus("PENDING"));
                counts.put("outboxOldestPendingMillis", outbox.oldestPendingAge().toMillis());
                dbCounts = Map.copyOf(counts);
            } catch (RuntimeException e) {
                dbCounts = Map.of("databaseLive", -1, "outboxPending", -1, "error", "database unavailable");
            }
            dbCountsAt = now;
        }
        return dbCounts;
    }

    private Map<String, Object> timer(String name) {
        Timer t = registry.find(name).timer();
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) {
            m.put("count", 0);
            return m;
        }
        m.put("count", t.count());
        for (ValueAtPercentile v : t.takeSnapshot().percentileValues()) {
            m.put("p" + Math.round(v.percentile() * 100) + "Millis", Math.round(v.value(TimeUnit.MILLISECONDS) * 100) / 100.0);
        }
        m.put("maxMillis", Math.round(t.max(TimeUnit.MILLISECONDS) * 100) / 100.0);
        return m;
    }
}
