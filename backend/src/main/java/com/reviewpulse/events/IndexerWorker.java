package com.reviewpulse.events;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetOutOfRangeException;
import org.apache.kafka.common.PartitionInfo;
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
 * partition, the next offset NOT yet reflected in the index ({@code positions}). It never commits
 * a position past a record it did not apply or durably dead-letter.
 *
 * <h2>Rebuild protocol (no lost updates)</h2>
 * <ol>
 *   <li>Read the topic's end offsets E.</li>
 *   <li>Scan PostgreSQL into a new index (one consistent snapshot).</li>
 *   <li>Swap the index in and continue consuming from E.</li>
 * </ol>
 * Every event below E was published after its transaction committed, so the scan (which starts
 * after E was read) already contains it. Events at or after E are replayed; ones the scan already
 * reflected are dropped by the version check. If Kafka is down, the index is still built so search
 * works, and consumption later starts from the beginning of the topic (correct, just slower).
 *
 * <h2>Failure handling</h2>
 * Poison events go to the dead-letter topic. If even that fails, the consumer rewinds to the poison
 * record and retries with backoff: progress stops, visibly (state DEGRADED), instead of data being lost.
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

    private final LinkedBlockingQueue<String> rebuildRequests = new LinkedBlockingQueue<>();
    private final AtomicBoolean rebuildPending = new AtomicBoolean(false);
    private volatile boolean running;
    private volatile Thread thread;
    private volatile KafkaConsumer<String, byte[]> activeConsumer;

    // Owned by the indexer thread only.
    private Map<TopicPartition, Long> positions;
    private boolean replayFromBeginning;
    private String degradedReason;

    public IndexerWorker(ReviewPulseProperties properties, KafkaTopics topics, IndexBuilder builder, SearchIndex index,
                         IndexStatus status, ReviewEventCodec codec, DeadLetterPublisher deadLetters,
                         IndexerProgress progress) {
        this.kafka = properties.kafka();
        this.indexConfig = properties.index();
        this.topics = topics;
        this.builder = builder;
        this.index = index;
        this.status = status;
        this.codec = codec;
        this.deadLetters = deadLetters;
        this.progress = progress;
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

    // ------------------------------------------------------------------ lifecycle

    @Override
    public void start() {
        running = true;
        rebuildPending.set(true);
        rebuildRequests.offer("startup");
        thread = new Thread(indexConfig.indexerEnabled() ? this::run : this::runWithoutKafka, "indexer");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        running = false;
        KafkaConsumer<String, byte[]> consumer = activeConsumer;
        if (consumer != null) {
            consumer.wakeup();
        }
        Thread t = thread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(TimeUnit.SECONDS.toMillis(15));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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
        topics.ensureTopics();
        List<PartitionInfo> infos = consumer.partitionsFor(kafka.topic(), Duration.ofMillis(kafka.apiTimeoutMillis()));
        List<TopicPartition> partitions = infos.stream()
                .map(p -> new TopicPartition(p.topic(), p.partition()))
                .sorted((a, b) -> Integer.compare(a.partition(), b.partition()))
                .toList();
        consumer.assign(partitions);
        return partitions;
    }

    /** Decides where consumption starts: rebuild, replay from the beginning, or resume. */
    private void positionConsumer(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        String reason = rebuildRequests.poll();
        if (reason != null || (positions == null && !replayFromBeginning)) {
            rebuildWithOffsets(consumer, partitions, reason != null ? reason : "startup", true);
        } else if (replayFromBeginning) {
            Map<TopicPartition, Long> beginning = consumer.beginningOffsets(partitions,
                    Duration.ofMillis(kafka.apiTimeoutMillis()));
            log.info("index was built while Kafka was unreachable; replaying topic from the beginning {}", beginning);
            positions = new HashMap<>(beginning);
            replayFromBeginning = false;
            seekAll(consumer, partitions);
        } else {
            seekAll(consumer, partitions);
        }
        markLiveIfHealthy();
    }

    private void consume(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions) {
        long nextLagRefresh = 0;
        while (running) {
            String reason = rebuildRequests.poll();
            if (reason != null) {
                rebuildWithOffsets(consumer, partitions, reason, false);
            }
            ConsumerRecords<String, byte[]> records;
            try {
                records = consumer.poll(Duration.ofMillis(kafka.pollTimeoutMillis()));
            } catch (OffsetOutOfRangeException e) {
                log.error("offsets {} are no longer in Kafka (retention); rebuilding from PostgreSQL", e.offsetOutOfRangePartitions());
                rebuildWithOffsets(consumer, partitions, "offsets lost to retention", true);
                continue;
            }
            if (!records.isEmpty()) {
                process(consumer, partitions, records);
            }
            long now = System.currentTimeMillis();
            if (now >= nextLagRefresh) {
                refreshLag(consumer, partitions);
                nextLagRefresh = now + LAG_REFRESH_MILLIS;
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

    // ------------------------------------------------------------------ rebuilds

    private void rebuildWithOffsets(KafkaConsumer<String, byte[]> consumer, List<TopicPartition> partitions,
                                    String reason, boolean required) {
        log.info("index rebuild started (reason: {})", reason);
        status.transition(IndexState.BUILDING, "rebuilding from PostgreSQL (" + reason + ")");
        try {
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions, Duration.ofMillis(kafka.apiTimeoutMillis()));
            IndexBuilder.Built built = builder.buildFromDatabase();
            index.replace(built.index());
            positions = new HashMap<>(end);
            replayFromBeginning = false;
            seekAll(consumer, partitions);
            status.buildCompleted(built.millis(), IndexState.LIVE, "built from PostgreSQL; consuming from " + describe(end));
            publishPositions();
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
            IndexBuilder.Built built = builder.buildFromDatabase();
            index.replace(built.index());
            positions = null;
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
