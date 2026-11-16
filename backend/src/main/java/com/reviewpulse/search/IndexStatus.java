package com.reviewpulse.search;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Observable state of the index. Updated by whoever builds or feeds the index; read by search,
 * health and metrics. Immutable snapshots are swapped atomically.
 */
@Component
public class IndexStatus {

    /** Immutable view of the status. */
    public record Snapshot(IndexState state, String detail, Instant since, Instant lastBuildCompletedAt,
                           long lastBuildMillis, boolean hasServedIndex) {
    }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(IndexState.EMPTY, "not built yet", Instant.now(), null, 0, false));

    public Snapshot snapshot() {
        return current.get();
    }

    public void transition(IndexState state, String detail) {
        current.updateAndGet(s -> new Snapshot(state, detail, Instant.now(), s.lastBuildCompletedAt(),
                s.lastBuildMillis(), s.hasServedIndex()));
    }

    public void buildCompleted(long millis) {
        current.set(new Snapshot(IndexState.LIVE, "built from PostgreSQL", Instant.now(), Instant.now(), millis, true));
    }

    public void buildFailed(String detail) {
        current.updateAndGet(s -> new Snapshot(s.hasServedIndex() ? IndexState.DEGRADED : IndexState.FAILED,
                detail, Instant.now(), s.lastBuildCompletedAt(), s.lastBuildMillis(), s.hasServedIndex()));
    }
}
