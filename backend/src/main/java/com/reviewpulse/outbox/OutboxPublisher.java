package com.reviewpulse.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.RetriableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.reviewpulse.config.ReviewPulseProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Moves committed outbox rows to Kafka.
 *
 * <p>Per batch, inside one database transaction: lock pending rows (SKIP LOCKED), send them all, wait
 * for broker acknowledgements, mark the acknowledged rows PUBLISHED, commit. Guarantees:
 * <ul>
 *   <li>An event is never published for a change that rolled back (the row would not exist).</li>
 *   <li>An event is marked PUBLISHED only after Kafka acknowledged it with acks=all.</li>
 *   <li>A crash between the acknowledgement and the commit re-sends the event later: delivery is
 *       at-least-once, and the indexer drops the duplicate by version.</li>
 *   <li>Failures back off exponentially (capped) and are counted; rows stay PENDING. Only
 *       non-retriable errors after repeated attempts move a row to FAILED.</li>
 * </ul>
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final int MAX_BATCHES_PER_RUN = 20;
    private static final int PERMANENT_FAILURE_ATTEMPTS = 5;

    private final OutboxRepository outbox;
    private final KafkaProducer<String, byte[]> producer;
    private final TransactionTemplate tx;
    private final ReviewPulseProperties.Outbox config;
    private final String topic;
    private final Counter published;
    private final Counter failures;
    private volatile long backoffMillis;
    private volatile Instant nextAttemptAt = Instant.EPOCH;
    private volatile Instant lastSuccessAt;
    private volatile String lastError;

    public OutboxPublisher(OutboxRepository outbox, KafkaProducer<String, byte[]> producer,
                           PlatformTransactionManager txManager, ReviewPulseProperties properties, MeterRegistry registry) {
        this.outbox = outbox;
        this.producer = producer;
        this.tx = new TransactionTemplate(txManager);
        this.config = properties.outbox();
        this.topic = properties.kafka().topic();
        this.published = Counter.builder("reviewpulse.outbox.published").register(registry);
        this.failures = Counter.builder("reviewpulse.outbox.publish.failures").register(registry);
    }

    @Scheduled(fixedDelayString = "${reviewpulse.outbox.poll-interval-millis}")
    public void run() {
        if (Instant.now().isBefore(nextAttemptAt)) {
            return;
        }
        try {
            for (int i = 0; i < MAX_BATCHES_PER_RUN; i++) {
                BatchOutcome outcome = publishBatch();
                if (outcome.failed() > 0) {
                    scheduleBackoff();
                    return;
                }
                backoffMillis = 0;
                if (outcome.attempted() < config.batchSize()) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("outbox publishing failed: {}", lastError);
            failures.increment();
            scheduleBackoff();
        }
    }

    record BatchOutcome(int attempted, int succeeded, int failed) {
    }

    /** Publishes one batch. Package-visible so tests can drive it deterministically. */
    BatchOutcome publishBatch() {
        return tx.execute(status -> {
            List<OutboxRepository.PendingEvent> rows = outbox.lockPending(config.batchSize());
            if (rows.isEmpty()) {
                return new BatchOutcome(0, 0, 0);
            }
            List<Future<RecordMetadata>> futures = new ArrayList<>(rows.size());
            for (OutboxRepository.PendingEvent row : rows) {
                futures.add(producer.send(toRecord(row)));
            }
            List<Long> ok = new ArrayList<>(rows.size());
            int failed = 0;
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.sendTimeoutMillis());
            for (int i = 0; i < rows.size(); i++) {
                OutboxRepository.PendingEvent row = rows.get(i);
                try {
                    long remaining = Math.max(1, deadline - System.nanoTime());
                    futures.get(i).get(remaining, TimeUnit.NANOSECONDS);
                    ok.add(row.id());
                } catch (ExecutionException | TimeoutException e) {
                    failed++;
                    Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                    boolean permanent = !(cause instanceof RetriableException) && !(cause instanceof TimeoutException)
                            && row.attempts() + 1 >= PERMANENT_FAILURE_ATTEMPTS;
                    lastError = cause.getClass().getSimpleName() + ": " + cause.getMessage();
                    outbox.recordFailure(row.id(), lastError, permanent);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while publishing", e);
                }
            }
            outbox.markPublished(ok);
            published.increment(ok.size());
            if (failed > 0) {
                failures.increment(failed);
                log.warn("outbox batch: {} published, {} failed ({})", ok.size(), failed, lastError);
            } else {
                lastSuccessAt = Instant.now();
            }
            return new BatchOutcome(rows.size(), ok.size(), failed);
        });
    }

    private ProducerRecord<String, byte[]> toRecord(OutboxRepository.PendingEvent row) {
        ProducerRecord<String, byte[]> record = new ProducerRecord<>(topic, null, Long.toString(row.reviewId()),
                row.payload().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventType", row.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("reviewVersion", Long.toString(row.reviewVersion()).getBytes(StandardCharsets.UTF_8));
        return record;
    }

    private void scheduleBackoff() {
        backoffMillis = backoffMillis == 0 ? 200 : Math.min(config.maxBackoffMillis(), backoffMillis * 2);
        nextAttemptAt = Instant.now().plusMillis(backoffMillis);
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.HOURS, initialDelay = 1)
    public void purgePublished() {
        int deleted = outbox.deletePublishedBefore(Instant.now().minus(Duration.ofHours(config.retentionHours())));
        if (deleted > 0) {
            log.info("purged {} published outbox rows older than {} h", deleted, config.retentionHours());
        }
    }

    public long currentBackoffMillis() {
        return backoffMillis;
    }

    public Instant lastSuccessAt() {
        return lastSuccessAt;
    }

    public String lastError() {
        return lastError;
    }
}
