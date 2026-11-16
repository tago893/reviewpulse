package com.reviewpulse.events;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.search.SnapshotStore;
import com.reviewpulse.search.index.IndexSnapshotCodec;
import com.reviewpulse.search.index.InvertedIndex;
import com.reviewpulse.search.index.SearchIndex;
import com.reviewpulse.search.tokenizer.Tokenizer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Checkpointing of the index together with the Kafka offsets it reflects.
 *
 * <p>A checkpoint is the recovery point: on restart the indexer loads it and replays the topic from
 * its offsets. Replaying events the snapshot already contains is harmless (version check), so the only
 * requirement is that the stored offsets are never AHEAD of the stored index, which holds because both
 * are taken together on the single writer thread.
 */
@Component
public class IndexCheckpoints {

    private static final Logger log = LoggerFactory.getLogger(IndexCheckpoints.class);

    /** A snapshot accepted for recovery. */
    public record Restored(InvertedIndex index, Map<TopicPartition, Long> positions, long importRunId,
                           long ageMillis, long loadMillis, long bytes) {
    }

    /** Observable checkpoint state. */
    public record Status(boolean enabled, Instant lastSavedAt, long lastSavedBytes, long lastSaveMillis,
                         Map<String, Long> lastSavedOffsets, long failures, String lastError, String lastRestore,
                         String file) {
    }

    private final SnapshotStore store;
    private final SearchIndex index;
    private final boolean enabled;
    private final Counter failureCounter;
    private final Timer saveTimer;
    private volatile Instant lastSavedAt;
    private volatile long lastSavedBytes;
    private volatile long lastSaveMillis;
    private volatile Map<String, Long> lastSavedOffsets = Map.of();
    private volatile long failures;
    private volatile String lastError;
    private volatile String lastRestore = "none";

    public IndexCheckpoints(SnapshotStore store, SearchIndex index, ReviewPulseProperties properties,
                            MeterRegistry registry) {
        this.store = store;
        this.index = index;
        this.enabled = properties.index().snapshotEnabled();
        this.failureCounter = Counter.builder("reviewpulse.index.snapshot.failures").register(registry);
        this.saveTimer = Timer.builder("reviewpulse.index.snapshot.duration").register(registry);
    }

    public boolean enabled() {
        return enabled;
    }

    /**
     * Loads and validates the snapshot. Returns empty (and records why) if it is missing or must not
     * be trusted, in which case the caller rebuilds from PostgreSQL.
     */
    public Optional<Restored> restore(String topicId, List<TopicPartition> partitions,
                                      Map<TopicPartition, Long> beginning, Map<TopicPartition, Long> end,
                                      long latestImportRun) {
        if (!enabled) {
            return Optional.empty();
        }
        SnapshotStore.LoadResult result = store.load();
        switch (result) {
            case SnapshotStore.Missing m -> {
                lastRestore = "no snapshot found";
                return Optional.empty();
            }
            case SnapshotStore.Corrupt c -> {
                lastRestore = "snapshot rejected: " + c.reason();
                return Optional.empty();
            }
            case SnapshotStore.Found found -> {
                IndexSnapshotCodec.Header h = found.snapshot().header();
                String problem = validate(h, topicId, partitions, beginning, end, latestImportRun);
                if (problem != null) {
                    lastRestore = "snapshot rejected: " + problem;
                    log.warn("not using index snapshot: {}", problem);
                    return Optional.empty();
                }
                Map<TopicPartition, Long> positions = new HashMap<>();
                h.offsets().forEach((p, o) -> positions.put(new TopicPartition(partitions.getFirst().topic(), p), o));
                long age = System.currentTimeMillis() - h.createdAtMillis();
                lastRestore = "restored snapshot from " + Instant.ofEpochMilli(h.createdAtMillis()) + " in "
                        + found.millis() + " ms";
                return Optional.of(new Restored(found.snapshot().index(), positions, h.importRunId(), age,
                        found.millis(), found.bytes()));
            }
        }
    }

    private static String validate(IndexSnapshotCodec.Header h, String topicId, List<TopicPartition> partitions,
                                   Map<TopicPartition, Long> beginning, Map<TopicPartition, Long> end,
                                   long latestImportRun) {
        if (!Tokenizer.FINGERPRINT.equals(h.tokenizerFingerprint())) {
            return "tokenizer rules changed since the snapshot was written";
        }
        if (!topicId.equals(h.topicId())) {
            return "Kafka topic was recreated (topic id changed)";
        }
        if (h.importRunId() != latestImportRun) {
            return "a bulk import (run " + latestImportRun + ") is newer than the snapshot (run " + h.importRunId() + ")";
        }
        if (h.offsets().size() != partitions.size()) {
            return "partition count changed";
        }
        for (TopicPartition tp : partitions) {
            Long offset = h.offsets().get(tp.partition());
            if (offset == null) {
                return "snapshot has no offset for " + tp;
            }
            if (offset < beginning.getOrDefault(tp, 0L)) {
                return "events after the snapshot were deleted by retention on " + tp;
            }
            if (offset > end.getOrDefault(tp, 0L)) {
                return "snapshot is ahead of the topic on " + tp + " (topic truncated?)";
            }
        }
        return null;
    }

    /**
     * Writes a checkpoint of the current index at {@code positions}. Must run on the single writer
     * thread. Returns true only once the snapshot file is durable.
     */
    public boolean save(Map<TopicPartition, Long> positions, String topicId, long importRunId) {
        if (!enabled) {
            return false;
        }
        long start = System.nanoTime();
        Map<Integer, Long> offsets = new TreeMap<>();
        positions.forEach((tp, o) -> offsets.put(tp.partition(), o));
        try {
            long generation = index.generation();
            InvertedIndex compact = index.compactedForSnapshot();
            IndexSnapshotCodec.Header header = new IndexSnapshotCodec.Header(Tokenizer.FINGERPRINT, topicId,
                    importRunId, System.currentTimeMillis(), generation, offsets);
            long bytes = store.write(compact, header);
            long nanos = System.nanoTime() - start;
            saveTimer.record(nanos, TimeUnit.NANOSECONDS);
            lastSavedAt = Instant.now();
            lastSavedBytes = bytes;
            lastSaveMillis = nanos / 1_000_000;
            Map<String, Long> described = new TreeMap<>();
            positions.forEach((tp, o) -> described.put(tp.toString(), o));
            lastSavedOffsets = Map.copyOf(described);
            lastError = null;
            log.info("index snapshot written: {} bytes, {} live docs, offsets {} in {} ms", bytes,
                    compact.liveDocCount(), offsets, lastSaveMillis);
            return true;
        } catch (IOException | RuntimeException e) {
            failures++;
            failureCounter.increment();
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("index snapshot failed; Kafka offsets are NOT committed, recovery point unchanged: {}", lastError);
            return false;
        }
    }

    public Status status() {
        return new Status(enabled, lastSavedAt, lastSavedBytes, lastSaveMillis, lastSavedOffsets, failures, lastError,
                lastRestore, store.file().toString());
    }

    public boolean healthy() {
        return !enabled || lastError == null;
    }
}
