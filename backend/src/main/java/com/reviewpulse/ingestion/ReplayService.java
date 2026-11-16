package com.reviewpulse.ingestion;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.reviewpulse.api.ConflictException;
import com.reviewpulse.api.RequestValidationException;
import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.reviews.Review;
import com.reviewpulse.reviews.ReviewService;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Replays a timestamp-sorted slice of real reviews as live arrivals through the normal write path
 * (PostgreSQL + outbox + Kafka), at a configurable rate. Rates are workload settings, not promises:
 * the status reports the rate actually achieved.
 *
 * <p>For reliability testing it can inject, deterministically for a given seed:
 * <ul>
 *   <li>duplicates: the just-created event is published to Kafka a second time;</li>
 *   <li>out-of-order pairs: a helpful vote creates version 2, then version 2 and then version 1 are
 *       published directly, so the indexer sees an older version after a newer one.</li>
 * </ul>
 * Original review timestamps are preserved ({@code reviewedAt}); processing time is recorded separately
 * ({@code createdAt}/{@code updatedAt}, event {@code occurredAt}).
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    public static final double MAX_RATE = 2_000;

    /** Parameters of a replay run. */
    public record StartRequest(String file, Double eventsPerSecond, Long limit, Long seed, Double duplicateRate,
                               Double outOfOrderRate, Integer writers) {
    }

    public static final int MAX_WRITERS = 16;

    /** Observable state. */
    public record Status(String state, String file, long position, long total, double targetRate, double achievedRate,
                         long created, long skippedExisting, long injectedDuplicates, long injectedOutOfOrder,
                         long failures, long seed, Instant startedAt, String lastError) {
    }

    private final ReplayFiles files;
    private final ReviewService reviews;
    private final KafkaProducer<String, byte[]> producer;
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final String topic;

    private final Object lock = new Object();
    private volatile Thread worker;
    private volatile boolean paused;
    private volatile boolean cancelled;
    private volatile double rate;
    private volatile String file;
    private volatile long total;
    private volatile long seed;
    private volatile Instant startedAt;
    private volatile String lastError;
    private volatile long windowStartNanos = System.nanoTime();
    private volatile long windowSent;
    private final AtomicLong position = new AtomicLong();
    private final AtomicLong created = new AtomicLong();
    private final AtomicLong skipped = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong outOfOrder = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public ReplayService(ReviewPulseProperties properties, ReviewService reviews, KafkaProducer<String, byte[]> producer,
                         JdbcClient jdbc, JsonMapper json, ReplayFilesHolder filesHolder) {
        this.files = filesHolder.files();
        this.reviews = reviews;
        this.producer = producer;
        this.jdbc = jdbc;
        this.json = json;
        this.topic = properties.kafka().topic();
    }

    /** Spring-friendly holder so the replay directory comes from configuration. */
    public record ReplayFilesHolder(ReplayFiles files) {
    }

    public List<String> availableFiles() {
        return files.list();
    }

    public Status start(StartRequest req) {
        double r = validRate(req.eventsPerSecond() == null ? 50.0 : req.eventsPerSecond());
        double dup = validProbability(req.duplicateRate(), "duplicateRate");
        double ooo = validProbability(req.outOfOrderRate(), "outOfOrderRate");
        long limit = req.limit() == null ? Long.MAX_VALUE : req.limit();
        if (limit < 1) {
            throw new RequestValidationException(List.of("limit must be >= 1"));
        }
        int writerCount = req.writers() == null ? 1 : req.writers();
        if (writerCount < 1 || writerCount > MAX_WRITERS) {
            throw new RequestValidationException(List.of("writers must be between 1 and " + MAX_WRITERS));
        }
        Path path = files.resolve(req.file());
        synchronized (lock) {
            if (worker != null && worker.isAlive()) {
                throw new ConflictException("REPLAY_RUNNING", "a replay is already running; stop it first");
            }
            long lines = countLines(path);
            file = req.file();
            total = Math.min(lines, limit);
            seed = req.seed() == null ? 42L : req.seed();
            rate = r;
            paused = false;
            cancelled = false;
            lastError = null;
            startedAt = Instant.now();
            for (AtomicLong c : List.of(position, created, skipped, duplicates, outOfOrder, failures)) {
                c.set(0);
            }
            long runLimit = total;
            long runSeed = seed;
            worker = new Thread(() -> run(path, runLimit, runSeed, dup, ooo, writerCount), "replay");
            worker.setDaemon(true);
            worker.start();
        }
        log.info("replay started: file={} records={} rate={}/s seed={} dup={} ooo={}", file, total, r, seed, dup, ooo);
        return status();
    }

    public Status stop() {
        paused = true;
        return status();
    }

    public Status resume() {
        if (worker == null || !worker.isAlive()) {
            throw new ConflictException("REPLAY_NOT_RUNNING", "no replay to resume; start one");
        }
        paused = false;
        LockSupport.unpark(worker);
        return status();
    }

    public Status setRate(Double eventsPerSecond) {
        rate = validRate(eventsPerSecond == null ? -1 : eventsPerSecond);
        Thread t = worker;
        if (t != null) {
            LockSupport.unpark(t);
        }
        return status();
    }

    public Status status() {
        Thread t = worker;
        String state = t == null ? "IDLE" : !t.isAlive() ? (cancelled ? "CANCELLED" : "COMPLETED") : paused ? "PAUSED" : "RUNNING";
        double windowSeconds = (System.nanoTime() - windowStartNanos) / 1e9;
        double achieved = "RUNNING".equals(state) && windowSeconds > 0.5 ? windowSent / windowSeconds : 0.0;
        return new Status(state, file, position.get(), total, rate, Math.round(achieved * 10) / 10.0, created.get(),
                skipped.get(), duplicates.get(), outOfOrder.get(), failures.get(), seed, startedAt, lastError);
    }

    @PreDestroy
    void shutdown() {
        cancelled = true;
        Thread t = worker;
        if (t != null) {
            LockSupport.unpark(t);
        }
    }

    // ------------------------------------------------------------------ worker

    /**
     * One pacing thread (this one) reads records in timestamp order and hands each to {@code writers}
     * concurrent writer threads through a small bounded queue, emulating that many clients. With one
     * writer the order is strictly sequential; with several, arrivals overlap like real traffic.
     * Injection decisions are drawn here, in record order, so a seed stays deterministic.
     */
    private void run(Path path, long limit, long runSeed, double dupRate, double oooRate, int writers) {
        Random random = new Random(runSeed);
        java.util.concurrent.ThreadPoolExecutor pool = new java.util.concurrent.ThreadPoolExecutor(writers, writers,
                0, TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(writers * 4),
                r -> new Thread(r, "replay-writer"), new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            double windowRate = rate;
            resetWindow();
            String line;
            long submitted = 0;
            while (!cancelled && submitted < limit && (line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                if (paused) {
                    while (paused && !cancelled) {
                        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(200));
                    }
                    resetWindow();
                }
                if (windowRate != rate) {
                    windowRate = rate;
                    resetWindow();
                }
                // Open-loop pacing: record k of the window is due at windowStart + k / rate.
                long due = windowStartNanos + (long) (windowSent * 1e9 / windowRate);
                long wait = due - System.nanoTime();
                if (wait > 0) {
                    LockSupport.parkNanos(wait);
                }
                boolean injectDuplicate = random.nextDouble() < dupRate;
                boolean injectOutOfOrder = random.nextDouble() < oooRate;
                String record = line;
                pool.execute(() -> {
                    replayOne(record, injectDuplicate, injectOutOfOrder);
                    position.incrementAndGet();
                });
                submitted++;
                windowSent++;
            }
        } catch (IOException | RuntimeException e) {
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.error("replay stopped by error", e);
        } finally {
            pool.shutdown();
            try {
                pool.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        log.info("replay finished: {}", status());
    }

    private void resetWindow() {
        windowStartNanos = System.nanoTime();
        windowSent = 0;
    }

    private void replayOne(String line, boolean injectDuplicate, boolean injectOutOfOrder) {
        try {
            Optional<Review> review = reviews.importReplayed(parse(line));
            if (review.isEmpty()) {
                skipped.incrementAndGet();
                return;
            }
            created.incrementAndGet();
            long id = review.get().id();
            if (injectDuplicate) {
                publishStored(id, 1);
                duplicates.incrementAndGet();
            }
            if (injectOutOfOrder) {
                Review voted = reviews.markHelpful(id);
                publishStored(id, voted.version());
                publishStored(id, 1);
                outOfOrder.incrementAndGet();
            }
        } catch (RuntimeException e) {
            failures.incrementAndGet();
            lastError = e.getClass().getSimpleName() + ": " + e.getMessage();
            log.warn("replay record failed: {}", lastError);
        }
    }

    /** Publishes the stored outbox payload of (review, version) directly to Kafka, bypassing the outbox. */
    private void publishStored(long reviewId, long version) {
        String payload = jdbc.sql("SELECT payload::text FROM outbox_events WHERE review_id = :id AND review_version = :v")
                .param("id", reviewId).param("v", version).query(String.class).single();
        try {
            producer.send(new ProducerRecord<>(topic, Long.toString(reviewId), payload.getBytes(StandardCharsets.UTF_8)))
                    .get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("injected publish failed: " + e.getMessage(), e);
        }
    }

    private ReviewService.ReplayCommand parse(String line) {
        JsonNode n = json.readTree(line);
        return new ReviewService.ReplayCommand(Long.parseLong(n.get("id").asString()), n.get("sourceKey").asString(),
                n.get("parentAsin").asString(), n.get("asin").asString(), n.get("title").asString(),
                n.get("body").asString(), n.get("rating").asInt(), n.get("helpfulVotes").asInt(),
                n.get("verifiedPurchase").asBoolean(), Instant.parse(n.get("reviewedAt").asString()));
    }

    private static long countLines(Path path) {
        try (Stream<String> lines = Files.lines(path, StandardCharsets.UTF_8)) {
            return lines.filter(l -> !l.isBlank()).count();
        } catch (IOException e) {
            throw new RequestValidationException(List.of("replay file could not be read"));
        }
    }

    private static double validRate(double r) {
        if (!(r > 0) || r > MAX_RATE) {
            throw new RequestValidationException(List.of("eventsPerSecond must be in (0, " + (int) MAX_RATE + "]"));
        }
        return r;
    }

    private static double validProbability(Double p, String name) {
        double v = p == null ? 0.0 : p;
        if (v < 0 || v > 0.5) {
            throw new RequestValidationException(List.of(name + " must be between 0 and 0.5"));
        }
        return v;
    }
}
