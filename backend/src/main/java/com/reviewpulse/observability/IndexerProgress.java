package com.reviewpulse.observability;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import com.reviewpulse.search.index.ApplyOutcome;
import com.reviewpulse.search.index.BatchResult;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Counters and gauges describing the event-driven indexing path. Written by the indexer thread,
 * read by the API and exported through Micrometer (/actuator/prometheus).
 */
@Component
public class IndexerProgress {

    private final Map<ApplyOutcome, Counter> outcomes = new EnumMap<>(ApplyOutcome.class);
    private final Map<ApplyOutcome, AtomicLong> outcomeTotals = new EnumMap<>(ApplyOutcome.class);
    private final Counter deadLettered;
    private final Timer indexingDelay;
    private final AtomicLong eventsConsumed = new AtomicLong();
    private final AtomicLong deadLetterTotal = new AtomicLong();
    private volatile long lagEvents = -1;
    private volatile boolean kafkaReachable;
    private volatile Instant lastAppliedAt;
    private volatile long lastDelayMillis = -1;
    private volatile Map<String, Long> positions = Map.of();

    public IndexerProgress(MeterRegistry registry) {
        for (ApplyOutcome o : ApplyOutcome.values()) {
            outcomes.put(o, Counter.builder("reviewpulse.index.events").tag("outcome", o.name()).register(registry));
            outcomeTotals.put(o, new AtomicLong());
        }
        deadLettered = Counter.builder("reviewpulse.index.dead_lettered").register(registry);
        indexingDelay = Timer.builder("reviewpulse.index.delay")
                .description("time from the review change committing to the event being searchable")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        Gauge.builder("reviewpulse.index.kafka_lag", this, p -> p.lagEvents).register(registry);
    }

    public void recordBatch(BatchResult result, int consumed) {
        eventsConsumed.addAndGet(consumed);
        result.counts().forEach((outcome, n) -> {
            outcomes.get(outcome).increment(n);
            outcomeTotals.get(outcome).addAndGet(n);
        });
        lastAppliedAt = Instant.now();
    }

    public void recordDelay(Instant occurredAt) {
        long millis = Math.max(0, Duration.between(occurredAt, Instant.now()).toMillis());
        indexingDelay.record(millis, TimeUnit.MILLISECONDS);
        lastDelayMillis = millis;
    }

    public void recordDeadLetter() {
        deadLettered.increment();
        deadLetterTotal.incrementAndGet();
        eventsConsumed.incrementAndGet();
    }

    public void lag(long events) {
        lagEvents = events;
    }

    public void kafkaReachable(boolean reachable) {
        kafkaReachable = reachable;
    }

    public void positions(Map<String, Long> current) {
        positions = Map.copyOf(current);
    }

    /** Immutable view for the API. */
    public record View(long eventsConsumed, Map<ApplyOutcome, Long> outcomes, long deadLettered, long lagEvents,
                       boolean kafkaReachable, Instant lastAppliedAt, long lastDelayMillis,
                       double delayP50Millis, double delayP95Millis, double delayP99Millis, Map<String, Long> positions) {
    }

    public View view() {
        Map<ApplyOutcome, Long> totals = new EnumMap<>(ApplyOutcome.class);
        outcomeTotals.forEach((k, v) -> totals.put(k, v.get()));
        var snapshot = indexingDelay.takeSnapshot();
        double[] p = {-1, -1, -1};
        var values = snapshot.percentileValues();
        for (int i = 0; i < values.length && i < 3; i++) {
            p[i] = values[i].value(TimeUnit.MILLISECONDS);
        }
        return new View(eventsConsumed.get(), Map.copyOf(totals), deadLetterTotal.get(), lagEvents, kafkaReachable,
                lastAppliedAt, lastDelayMillis, p[0], p[1], p[2], positions);
    }

    public long lagEvents() {
        return lagEvents;
    }

    public boolean kafkaReachable() {
        return kafkaReachable;
    }
}
