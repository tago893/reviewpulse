package com.reviewpulse.events;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.observability.IndexerProgress;
import com.reviewpulse.reviews.ImportRunRepository;
import com.reviewpulse.search.IndexBuilder;
import com.reviewpulse.search.IndexState;
import com.reviewpulse.search.IndexStatus;
import com.reviewpulse.search.index.BatchResult;
import com.reviewpulse.search.index.IndexMutation;
import com.reviewpulse.search.index.SearchIndex;

/**
 * The single writer of the search index.
 *
 * <p>One thread owns a {@link KafkaConsumer} with manual partition assignment and keeps, per
 * partition, the next offset NOT yet reflected in the index ({@code positions}).
 *
 * <h2>Durability contract</h2>
 * Applied events are searchable immediately but durable only once a checkpoint (index snapshot plus
 * {@code positions}) has been written. Kafka offsets are committed only after that, and recovery starts
 * from the checkpoint's offsets. A crash therefore loses no events: everything after the checkpoint is
 * replayed, and anything replayed twice is dropped by the version check (effectively-once index state
 * over at-least-once delivery, not exactly-once processing).
 *
 * <h2>Rebuild protocol (no lost updates)</h2>
 * Read end offsets E, then scan PostgreSQL, swap the new index in and consume from E. Every event below
 * E was published after its transaction committed, so the scan (started after E was read) contains it.
 * If Kafka is down, the index is still built so search works, and consumption later starts from the
 * beginning of the topic (correct, just slower).
 *
 * <h2>Failure handling</h2>
 * Poison events go to the dead-letter topic. If that fails too, the consumer rewinds and retries with
 * backoff: progress stops, visibly (DEGRADED), instead of data being skipped.
 */
@Component
public class IndexerWorker implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(IndexerWorker.class);
    private static final long LAG_REFRESH_MILLIS = 2_000;
    private static final long MAX_BACKOFF_MILLIS = 10_000;

    private final ReviewPulseProperties.Kafka kafka;
    private final ReviewPulseProperties.Index indexConfig;
    private final KafkaTopics topics;
    private final IndexBuilder builder;
    private final SearchIndex index;
    private final IndexStatus status;
    private final ReviewEventCodec codec;
    private final DeadLetterPublisher deadLetters;
    private final IndexerProgress progress;
    private final IndexCheckpoints checkpoints;
    private final ImportRunRepository imports;

    private final LinkedBlockingQueue<String> rebuildRequests = new LinkedBlockingQueue<>();
    private final AtomicBoolean rebuildPending = new AtomicBoolean(false);
    private final AtomicBoolean snapshotRequested = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile Thread thread;
    private volatile KafkaConsumer<String, byte[]> activeConsumer;

    // Owned by the indexer thread only.
    private Map<TopicPartition, Long> positions;
    private boolean replayFromBeginning;
    private String degradedReason;
    private String topicId;
    private long indexedImportRunId;
    private long lastCheckpointGeneration = -1;
    private long nextCheckpointAt;
    private long nextImportCheckAt;

    public IndexerWorker(ReviewPulseProperties properties, KafkaTopics topics, IndexBuilder builder, SearchIndex index,
                         IndexStatus status, ReviewEventCodec codec, DeadLetterPublisher deadLetters,
                         IndexerProgress progress, IndexCheckpoints checkpoints, ImportRunRepository imports) {
        this.kafka = properties.kafka();
        this.indexConfig = properties.index();
        this.topics = topics;
        this.builder = builder;
        this.index = index;
        this.status = status;
        this.codec = codec;
        this.deadLetters = deadLetters;
        this.progress = progress;
        this.checkpoints = checkpoints;
        this.imports = imports;
    }

    // ------------------------------------------------------------------ public API

    /** Queues a full rebuild from PostgreSQL. Returns false if one is already pending or running. */
    public boolean requestRebuild(String reason) {
        if (!rebuildPending.compareAndSet(false, true)) {
            return false;
        }
        rebuildRequests.offer(reason);
        return true;
    }

    public boolean isRebuilding() {
        return rebuildPending.get();
    }

    /** Asks for a checkpoint at the next loop iteration (admin/testing). */
    public void requestSnapshot() {
        snapshotRequested.set(true);
    }

    public boolean isSnapshotPending() {
        return snapshotRequested.get();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        // A (re)start begins from durable state only, exactly like a new process.
        positions = null;
        replayFromBeginning = false;
        degradedReason = null;
        topicId = null;
        indexedImportRunId = 0;
        lastCheckpointGeneration = -1;
        rebuildRequests.clear();
        running = true;
        rebuildPending.set(true);
        rebuildRequests.offer("startup");
        thread = new Thread(indexConfig.indexerEnabled() ? this::run : this::runWithoutKafka, "indexer");
        thread.setDaemon(true);
        thread.start();
    }

    /** Graceful: the consumer is woken, the loop writes a final checkpoint and exits. */
    @Override
    public void stop() {
        running = false;
        KafkaConsumer<String, byte[]> consumer = activeConsumer;
        if (consumer != null) {
            consumer.wakeup();
        }
        Thread t = thread;
        if (t == null) {
            return;
        }
        try {
            t.join(TimeUnit.SECONDS.toMillis(20));
            if (t.isAlive()) {
                t.interrupt();
                t.join(TimeUnit.SECONDS.toMillis(5));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // ------------------------------------------------------------------ main loop

    private void run() {
        long backoff = 500;
        while (running) {
            try (KafkaConsumer<String, byte[]> consumer = newConsumer()) {
                activeConsumer = consumer;
                List<TopicPartition> partitions = connect(consumer);
                progress.kafkaReachable(true);
                positionConsumer(consumer, partitions);
                backoff = 500;
                consume(consumer, partitions);
            } catch (WakeupException e) {
                log.debug("indexer woken up for shutdown");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                progress.kafkaReachable(false);
                log.warn("indexer loop failed ({}); reconnecting in {} ms", e.toString(), backoff);
                handleKafkaOutage();
                if (!waitForRetry(backoff)) {
                    return;
                }
                backoff = Math.min(MAX_BACKOFF_MILLIS, backoff * 2);
            } finally {
                activeConsumer = null;
            }
        }
    }

    /** Used when the Kafka path is disabled by configuration: build once, never update. */
    private void runWithoutKafka() {
        rebuildRequests.poll();
        buildWithoutOffsets("indexer disabled by configuration");
    }

    private List<TopicPartition> connect(KafkaConsumer<String, byte[]> consumer) throws InterruptedException {
        topicId = topics.ensureTopics().toString();
        List<TopicPartition> partitions = consumer.partitionsFor(kafka.topic(), apiTimeout()).stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .sorted((a, b) -> Integer.compare(a.partition(), b.partition()))
                .toList();
        consumer.assign(partitions);
        return partitions;
    }

    /** Decides where consumption starts: restore a checkpoint, rebuild, replay from the beginning, or resume. */
    private void positionConsumer(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        String reason = rebuildRequests.poll();
        boolean fresh = positions == null && !replayFromBeginning;
        if (fresh && "startup".equals(reason) && restoreCheckpoint(consumer, partitions)) {
            log.info("index restored from checkpoint; replaying events after it");
        } else if (reason != null || fresh) {
            rebuildWithOffsets(consumer, partitions, reason != null ? reason : "startup", true);
        } else if (replayFromBeginning) {
            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions, apiTimeout());
            log.info("index was built while Kafka was unreachable; replaying topic from the beginning {}", beginning);
            positions = new HashMap<>(beginning);
            replayFromBeginning = false;
            seekAll(consumer, partitions);
        } else {
            seekAll(consumer, partitions);
        }
        markLiveIfHealthy();
    }

    private boolean restoreCheckpoint(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        status.transition(IndexState.BUILDING, "loading index checkpoint");
        Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions, apiTimeout());
        Map<TopicPartition, Long> end = consumer.endOffsets(partitions, apiTimeout());
        Optional<IndexCheckpoints.Restored> restored = checkpoints.restore(topicId, partitions, beginning, end,
                imports.latestCompletedId());
        if (restored.isEmpty()) {
            return false;
        }
        IndexCheckpoints.Restored r = restored.get();
        index.replace(r.index());
        positions = new HashMap<>(r.positions());
        indexedImportRunId = r.importRunId();
        lastCheckpointGeneration = index.generation();
        seekAll(consumer, partitions);
        long behind = 0;
        for (TopicPartition tp : partitions) {
            behind += end.getOrDefault(tp, 0L) - positions.getOrDefault(tp, 0L);
        }
        status.buildCompleted(r.loadMillis(), IndexState.LIVE,
                "restored from checkpoint (" + r.bytes() + " bytes, age " + r.ageMillis() / 1000 + " s); replaying "
                        + behind + " events");
        rebuildPending.set(false);
        publishPositions();
        return true;
    }

    private void consume(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        long nextLagRefresh = 0;
        nextCheckpointAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(indexConfig.snapshotIntervalSeconds());
        nextImportCheckAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(indexConfig.importCheckSeconds());
        while (running) {
            try {
                String reason = rebuildRequests.poll();
                if (reason != null) {
                    rebuildWithOffsets(consumer, partitions, reason, false);
                }
                ConsumerRecords<String, byte[]> records;
                try {
                    records = consumer.poll(Duration.ofMillis(kafka.pollTimeoutMillis()));
                } catch (OffsetOutOfRangeException e) {
                    log.error("offsets {} are no longer in Kafka (retention); rebuilding", e.offsetOutOfRangePartitions());
                    rebuildWithOffsets(consumer, partitions, "offsets lost to retention", true);
                    continue;
                }
                if (!records.isEmpty()) {
                    process(consumer, partitions, records);
                }
                runPeriodicTasks(consumer, partitions);
                long now = System.currentTimeMillis();
                if (now >= nextLagRefresh) {
                    refreshLag(consumer, partitions);
                    nextLagRefresh = now + LAG_REFRESH_MILLIS;
                }
            } catch (WakeupException e) {
                if (!running) {
                    checkpoint(consumer, "shutdown", true);
                    return;
                }
            }
        }
        checkpoint(consumer, "shutdown", true);
    }

    private void runPeriodicTasks(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        long now = System.currentTimeMillis();
        if (snapshotRequested.getAndSet(false)) {
            checkpoint(consumer, "requested", true);
        } else if (now >= nextCheckpointAt) {
            checkpoint(consumer, "periodic", false);
        }
        if (now >= nextImportCheckAt) {
            nextImportCheckAt = now + TimeUnit.SECONDS.toMillis(indexConfig.importCheckSeconds());
            try {
                long latest = imports.latestCompletedId();
                if (latest > indexedImportRunId && requestRebuild("bulk import run " + latest + " detected")) {
                    log.info("bulk import run {} is newer than the index (run {}); rebuilding", latest, indexedImportRunId);
                }
            } catch (org.springframework.dao.DataAccessException e) {
                // PostgreSQL being down must not disturb event consumption; check again next interval.
                log.warn("bulk-import check skipped: {}", e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ applying events

    private void process(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions,
                         ConsumerRecords<String, byte[]> records) {
        Pending pending = new Pending();
        for (ConsumerRecord<String, byte[]> record : records) {
            ReviewEvent event;
            try {
                event = codec.decode(record.value());
            } catch (PoisonEventException e) {
                if (!applyBatch(consumer, partitions, pending) || !deadLetter(consumer, partitions, record, e)) {
                    publishPositions();
                    return;
                }
                pending = new Pending();
                continue;
            }
            pending.add(record, event);
        }
        applyBatch(consumer, partitions, pending);
        publishPositions();
    }

    /** Events decoded from one poll, applied as a single atomic batch. */
    private static final class Pending {
        final List<IndexMutation> mutations = new ArrayList<>();
        final List<ConsumerRecord<String, byte[]>> records = new ArrayList<>();
        final List<Instant> occurredAt = new ArrayList<>();

        void add(ConsumerRecord<String, byte[]> record, ReviewEvent event) {
            mutations.add(event.toMutation());
            records.add(record);
            occurredAt.add(event.occurredAt());
        }
    }

    /**
     * Applies a batch atomically. If the index throws (a bug, since inputs are validated), the batch is
     * retried one event at a time so only the offending event is dead-lettered; events that were
     * already applied become version-checked no-ops on the retry.
     *
     * @return false if consumption had to be rewound
     */
    private boolean applyBatch(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions, Pending pending) {
        if (pending.mutations.isEmpty()) {
            return true;
        }
        try {
            record(pending, 0, pending.records.size(), index.apply(pending.mutations));
            return true;
        } catch (RuntimeException batchError) {
            log.error("applying a batch of {} events failed; isolating the failing event", pending.mutations.size(), batchError);
        }
        for (int i = 0; i < pending.mutations.size(); i++) {
            try {
                record(pending, i, i + 1, index.apply(List.of(pending.mutations.get(i))));
            } catch (RuntimeException e) {
                if (!deadLetter(consumer, partitions, pending.records.get(i), e)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void record(Pending pending, int from, int to, BatchResult result) {
        progress.recordBatch(result, to - from);
        for (int i = from; i < to; i++) {
            ConsumerRecord<String, byte[]> r = pending.records.get(i);
            positions.put(new TopicPartition(r.topic(), r.partition()), r.offset() + 1);
            if (result.outcomes().get(i - from).changedIndex()) {
                progress.recordDelay(pending.occurredAt.get(i));
            }
        }
    }

    /** @return false if the record could not be dead-lettered and consumption was rewound */
    private boolean deadLetter(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions,
                               ConsumerRecord<String, byte[]> record, RuntimeException error) {
        if (deadLetters.publish(record, error)) {
            progress.recordDeadLetter();
            positions.put(new TopicPartition(record.topic(), record.partition()), record.offset() + 1);
            degradedReason = null;
            return true;
        }
        degradedReason = "dead-letter topic unavailable; paused at " + record.topic() + "-" + record.partition()
                + "@" + record.offset();
        status.transition(IndexState.DEGRADED, degradedReason);
        seekAll(consumer, partitions);
        sleepQuietly(1_000);
        return false;
    }

    // ------------------------------------------------------------------ checkpoints

    /**
     * Writes a checkpoint when the index changed since the last one (or when forced), then commits the
     * checkpoint's offsets to Kafka. Offsets are never committed for state that is not durable.
     */
    private void checkpoint(KafkaConsumer<String, byte[]> consumer, String reason, boolean force) {
        nextCheckpointAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(indexConfig.snapshotIntervalSeconds());
        if (!checkpoints.enabled() || positions == null || topicId == null) {
            return;
        }
        long generation = index.generation();
        if (!force && generation == lastCheckpointGeneration) {
            return;
        }
        Map<TopicPartition, Long> at = Map.copyOf(positions);
        if (!checkpoints.save(at, topicId, indexedImportRunId)) {
            return;
        }
        lastCheckpointGeneration = generation;
        Map<TopicPartition, OffsetAndMetadata> commit = new HashMap<>();
        at.forEach((tp, offset) -> commit.put(tp, new OffsetAndMetadata(offset, "checkpoint:" + reason)));
        try {
            consumer.commitSync(commit, apiTimeout());
        } catch (WakeupException e) {
            throw e;
        } catch (RuntimeException e) {
            log.warn("committing checkpoint offsets to Kafka failed (the snapshot remains the recovery point): {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ rebuilds

    private void rebuildWithOffsets(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions,
                                    String reason, boolean required) {
        log.info("index rebuild started (reason: {})", reason);
        status.transition(IndexState.BUILDING, "rebuilding from PostgreSQL (" + reason + ")");
        try {
            long importRun = imports.latestCompletedId();
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, apiTimeout());
            IndexBuilder.Built built = builder.buildFromDatabase();
            index.replace(built.index());
            positions = new HashMap<>(end);
            indexedImportRunId = importRun;
            replayFromBeginning = false;
            seekAll(consumer, partitions);
            status.buildCompleted(built.millis(), IndexState.LIVE, "built from PostgreSQL; consuming from " + describe(end));
            publishPositions();
            rebuildPending.set(false);
            checkpoint(consumer, "after rebuild", true);
        } catch (WakeupException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("index rebuild failed", e);
            status.buildFailed("rebuild failed: " + e.getClass().getSimpleName());
            if (positions != null) {
                seekAll(consumer, partitions);
            }
            if (required) {
                throw e;
            }
        } finally {
            rebuildPending.set(false);
        }
    }

    /** Kafka is down: make sure search still has data, and remember that offsets are unknown. */
    private void handleKafkaOutage() {
        String reason = rebuildRequests.poll();
        if (reason != null || !status.snapshot().hasServedIndex()) {
            buildWithoutOffsets(reason != null ? reason : "startup without Kafka");
        }
        status.transition(IndexState.DEGRADED, "Kafka unreachable: live updates paused; serving last built index");
    }

    private void buildWithoutOffsets(String reason) {
        status.transition(IndexState.BUILDING, "rebuilding from PostgreSQL (" + reason + ")");
        try {
            long importRun = imports.latestCompletedId();
            IndexBuilder.Built built = builder.buildFromDatabase();
            index.replace(built.index());
            positions = null;
            indexedImportRunId = importRun;
            replayFromBeginning = true;
            status.buildCompleted(built.millis(), IndexState.DEGRADED,
                    "built from PostgreSQL without Kafka offsets; live updates resume when Kafka is reachable");
        } catch (RuntimeException e) {
            log.error("index build without Kafka failed", e);
            status.buildFailed("build failed: " + e.getClass().getSimpleName());
        } finally {
            rebuildPending.set(false);
        }
    }

    private boolean waitForRetry(long millis) {
        try {
            String reason = rebuildRequests.poll(millis, TimeUnit.MILLISECONDS);
            if (reason != null) {
                buildWithoutOffsets(reason);
            }
            return running;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    private void refreshLag(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        try {
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofMillis(2_000));
            long lag = 0;
            for (TopicPartition tp : partitions) {
                lag += Math.max(0, end.getOrDefault(tp, 0L) - positions.getOrDefault(tp, 0L));
            }
            progress.lag(lag);
            progress.kafkaReachable(true);
            markLiveIfHealthy();
        } catch (WakeupException e) {
            throw e;
        } catch (RuntimeException e) {
            progress.kafkaReachable(false);
            progress.lag(-1);
            status.transition(IndexState.DEGRADED, "Kafka unreachable: live updates paused; serving last built index");
        }
    }

    private void markLiveIfHealthy() {
        if (degradedReason == null && progress.kafkaReachable() && status.snapshot().hasServedIndex()
                && !rebuildPending.get()) {
            status.transition(IndexState.LIVE, "consuming live events");
        }
    }

    private void seekAll(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        for (TopicPartition tp : partitions) {
            Long offset = positions == null ? null : positions.get(tp);
            if (offset != null) {
                consumer.seek(tp, offset);
            } else {
                consumer.seekToBeginning(List.of(tp));
            }
        }
    }

    private void publishPositions() {
        if (positions != null) {
            progress.positions(describe(positions));
        }
    }

    private static Map<String, Long> describe(Map<TopicPartition, Long> offsets) {
        Map<String, Long> out = new TreeMap<>();
        offsets.forEach((tp, o) -> out.put(tp.toString(), o));
        return out;
    }

    private Duration apiTimeout() {
        return Duration.ofMillis(kafka.apiTimeoutMillis());
    }

    private KafkaConsumer<String, byte[]> newConsumer() {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, kafka.consumerGroup());
        p.put(ConsumerConfig.CLIENT_ID_CONFIG, "reviewpulse-indexer");
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // Never silently jump over missing offsets: surface them so the indexer can rebuild.
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "none");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafka.maxPollRecords());
        p.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) kafka.apiTimeoutMillis());
        p.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) kafka.apiTimeoutMillis());
        p.put(ConsumerConfig.RECONNECT_BACKOFF_MAX_MS_CONFIG, 5_000);
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(p);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
