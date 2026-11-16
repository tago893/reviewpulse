package com.reviewpulse.search;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Component;

/**
 * Observable lifecycle state of the index. Written only by the indexer thread; read by search,
 * health and metrics. Immutable snapshots are swapped atomically.
 */
@Component
public class IndexStatus {

    /** Immutable view of the status. */
    public record Snapshot(IndexState state, String detail, Instant since, Instant lastBuildCompletedAt,
                           long lastBuildMillis, boolean hasServedIndex) {
    }

    private final AtomicReference<Snapshot> current =
            new AtomicReference<>(new Snapshot(IndexState.EMPTY, "starting", Instant.now(), null, 0, false));

    public Snapshot snapshot() {
        return current.get();
    }

    /** Changes state/detail; a no-op if both are unchanged (keeps {@code since} meaningful). */
    public void transition(IndexState state, String detail) {
        current.updateAndGet(s -> s.state() == state && s.detail().equals(detail) ? s
                : new Snapshot(state, detail, Instant.now(), s.lastBuildCompletedAt(), s.lastBuildMillis(),
                s.hasServedIndex()));
    }

    public void buildCompleted(long millis, IndexState state, String detail) {
        Instant now = Instant.now();
        current.set(new Snapshot(state, detail, now, now, millis, true));
    }

    public void buildFailed(String detail) {
        current.updateAndGet(s -> new Snapshot(s.hasServedIndex() ? IndexState.DEGRADED : IndexState.FAILED,
                detail, Instant.now(), s.lastBuildCompletedAt(), s.lastBuildMillis(), s.hasServedIndex()));
    }
}
