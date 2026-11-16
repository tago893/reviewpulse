package com.reviewpulse.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.jayway.jsonpath.JsonPath;
import com.reviewpulse.api.ConflictException;
import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.observability.IndexerProgress;
import com.reviewpulse.reviews.Review;
import com.reviewpulse.reviews.ReviewService;
import com.reviewpulse.search.IndexState;
import com.reviewpulse.search.IndexStatus;
import com.reviewpulse.search.index.ApplyOutcome;
import com.reviewpulse.search.index.SearchIndex;
import com.reviewpulse.support.IntegrationTest;
import com.reviewpulse.support.TestcontainersConfiguration;

@IntegrationTest
class EventDrivenIndexingIT {

    private static final Duration EVENTUALLY = Duration.ofSeconds(20);
    private static final String ASIN = "B0EVENT001";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    ReviewService reviews;
    @Autowired
    SearchIndex index;
    @Autowired
    IndexStatus status;
    @Autowired
    IndexerProgress progress;
    @Autowired
    IndexerWorker indexer;
    @Autowired
    KafkaProducer<String, byte[]> producer;
    @Autowired
    ReviewPulseProperties properties;
    @Autowired
    PlatformTransactionManager txManager;

    private static String uniqueWord() {
        return "w" + Long.toString(System.nanoTime(), 36) + Integer.toString((int) (Math.random() * 1_000), 36);
    }

    private Review create(String title, String body) {
        return reviews.create(new ReviewService.CreateCommand(ASIN, title, body, 4, true));
    }

    private void awaitIndexed(long id, long version) {
        await().atMost(EVENTUALLY).until(() -> index.read(idx -> idx.appliedVersion(id)).orElse(0) >= version);
    }

    private List<String> searchIds(String q) {
        MvcTestResult r = mvc.get().uri("/api/search").param("q", q).param("operator", "OR").param("pageSize", "50").exchange();
        assertThat(r).hasStatusOk();
        return JsonPath.read(body(r), "$.data.results[*].id");
    }

    private static String body(MvcTestResult r) {
        try {
            return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createUpdateHelpfulAndDeleteFlowThroughOutboxKafkaAndIndex() {
        String oldWord = uniqueWord();
        String newWord = uniqueWord();

        MvcTestResult created = mvc.post().uri("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentAsin\":\"" + ASIN + "\",\"title\":\"First " + oldWord + "\",\"body\":\"about "
                        + oldWord + "\",\"rating\":3,\"verifiedPurchase\":true}")
                .exchange();
        assertThat(created).hasStatus(201).bodyJson().extractingPath("$.meta.indexing").isEqualTo("PENDING");
        String id = JsonPath.read(body(created), "$.data.id");
        assertThat(created).bodyJson().extractingPath("$.data.searchable").asBoolean().isFalse();

        await().atMost(EVENTUALLY).untilAsserted(() -> assertThat(mvc.get().uri("/api/reviews/" + id))
                .bodyJson().extractingPath("$.data.searchable").asBoolean().isTrue());
        assertThat(searchIds(oldWord)).containsExactly(id);

        MvcTestResult updated = mvc.put().uri("/api/reviews/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"title\":\"Edited\",\"body\":\"now about " + newWord + "\",\"rating\":5}")
                .exchange();
        assertThat(updated).hasStatusOk().bodyJson().extractingPath("$.data.version").asNumber().isEqualTo(2);
        awaitIndexed(Long.parseLong(id), 2);
        assertThat(searchIds(oldWord)).as("old terms are gone").isEmpty();
        assertThat(searchIds(newWord)).containsExactly(id);

        long postingsBefore = index.stats().postings();
        long metadataBefore = progress.view().outcomes().get(ApplyOutcome.METADATA_UPDATED);
        assertThat(mvc.post().uri("/api/reviews/" + id + "/helpful")).hasStatusOk();
        awaitIndexed(Long.parseLong(id), 3);
        assertThat(index.stats().postings()).as("helpful vote does not touch postings").isEqualTo(postingsBefore);
        assertThat(progress.view().outcomes().get(ApplyOutcome.METADATA_UPDATED)).isEqualTo(metadataBefore + 1);
        int helpfulInIndex = index.read(idx -> idx.helpfulVotes(idx.liveDocId(Long.parseLong(id)).orElseThrow()));
        assertThat(helpfulInIndex).isEqualTo(1);

        assertThat(mvc.delete().uri("/api/reviews/" + id + "?expectedVersion=2")).hasStatus(409)
                .bodyJson().extractingPath("$.error.code").isEqualTo("VERSION_CONFLICT");
        assertThat(mvc.delete().uri("/api/reviews/" + id + "?expectedVersion=3")).hasStatusOk();
        awaitIndexed(Long.parseLong(id), 4);
        assertThat(searchIds(newWord)).as("deleted review never returned").isEmpty();
        assertThat(mvc.get().uri("/api/reviews/" + id)).hasStatus(404);
    }

    @Test
    void duplicateAndOutOfOrderKafkaEventsAreIgnored() throws Exception {
        String v1Word = uniqueWord();
        String v2Word = uniqueWord();
        Review r1 = create("dup " + v1Word, v1Word);
        Review r2 = reviews.update(r1.id(), new ReviewService.UpdateCommand(1L, "dup", v2Word, 4));
        awaitIndexed(r1.id(), r2.version());

        List<String> payloads = jdbc.sql("SELECT payload::text FROM outbox_events WHERE review_id = :id ORDER BY review_version")
                .param("id", r1.id()).query(String.class).list();
        assertThat(payloads).hasSize(2);
        long staleBefore = progress.view().outcomes().get(ApplyOutcome.STALE_IGNORED);

        // Duplicate delivery of v2, then a delayed v1 arriving after v2.
        send(r1.id(), payloads.get(1).getBytes(StandardCharsets.UTF_8));
        send(r1.id(), payloads.get(0).getBytes(StandardCharsets.UTF_8));

        await().atMost(EVENTUALLY).until(() -> progress.view().outcomes().get(ApplyOutcome.STALE_IGNORED) >= staleBefore + 2);
        assertThat(index.read(idx -> idx.appliedVersion(r1.id())).orElseThrow()).isEqualTo(2);
        assertThat(searchIds(v1Word)).isEmpty();
        assertThat(searchIds(v2Word)).containsExactly(Long.toString(r1.id()));
    }

    @Test
    void poisonEventIsDeadLetteredAndIndexingContinues() throws Exception {
        byte[] garbage = ("{not valid json " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        long deadBefore = progress.view().deadLettered();
        send(424242L, garbage);

        String word = uniqueWord();
        Review after = create("after poison", word);
        awaitIndexed(after.id(), 1);
        assertThat(progress.view().deadLettered()).isEqualTo(deadBefore + 1);

        ConsumerRecord<String, byte[]> dead = findOnDeadLetterTopic(garbage);
        assertThat(new String(dead.headers().lastHeader("dlt.original.topic").value(), StandardCharsets.UTF_8))
                .isEqualTo(properties.kafka().topic());
        assertThat(new String(dead.headers().lastHeader("dlt.error.class").value(), StandardCharsets.UTF_8))
                .endsWith("PoisonEventException");
        assertThat(status.snapshot().state()).isEqualTo(IndexState.LIVE);
    }

    @Test
    void rolledBackWriteNeverPublishesAnEvent() {
        String word = uniqueWord();
        TransactionTemplate tx = new TransactionTemplate(txManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            create("rollback " + word, word);
            throw new IllegalStateException("simulated failure after the write");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE payload::text LIKE :w").param("w", "%" + word + "%")
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM reviews WHERE body = :w").param("w", word).query(Long.class).single()).isZero();

        Review sentinel = create("sentinel", uniqueWord());
        awaitIndexed(sentinel.id(), 1);
        assertThat(searchIds(word)).isEmpty();
    }

    @Test
    void concurrentUpdatesWithTheSameExpectedVersionHaveExactlyOneWinner() throws Exception {
        Review review = create("race", uniqueWord());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<Review>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            int n = i;
            results.add(pool.submit(() -> {
                try {
                    return reviews.update(review.id(), new ReviewService.UpdateCommand(1L, "race " + n, "body " + n, 2));
                } catch (ConflictException e) {
                    conflicts.incrementAndGet();
                    return null;
                }
            }));
        }
        long winners = 0;
        for (Future<Review> f : results) {
            winners += f.get(30, TimeUnit.SECONDS) == null ? 0 : 1;
        }
        pool.shutdown();
        assertThat(winners).isEqualTo(1);
        assertThat(conflicts.get()).isEqualTo(7);
        assertThat(jdbc.sql("SELECT version FROM reviews WHERE id = :id").param("id", review.id()).query(Long.class).single())
                .isEqualTo(2);
    }

    @Test
    void concurrentHelpfulVotesAreAllCountedAndIndexed() throws Exception {
        Review review = create("votes", uniqueWord());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Review>> votes = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            votes.add(() -> reviews.markHelpful(review.id()));
        }
        for (Future<Review> f : pool.invokeAll(votes, 60, TimeUnit.SECONDS)) {
            f.get();
        }
        pool.shutdown();
        awaitIndexed(review.id(), 21);
        int helpful = index.read(idx -> idx.helpfulVotes(idx.liveDocId(review.id()).orElseThrow()));
        assertThat(helpful).isEqualTo(20);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox_events WHERE review_id = :id").param("id", review.id())
                .query(Long.class).single()).as("one event per version").isEqualTo(21);
    }

    @Test
    void rebuildDuringConcurrentWritesLosesNothing() throws Exception {
        String word = uniqueWord();
        ExecutorService writer = Executors.newSingleThreadExecutor();
        Future<List<Review>> written = writer.submit(() -> {
            List<Review> out = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                out.add(create("rebuild " + word, "item " + i));
                if (i == 10) {
                    await().atMost(EVENTUALLY).until(() -> indexer.requestRebuild("test: rebuild during writes"));
                }
            }
            return out;
        });
        List<Review> created = written.get(60, TimeUnit.SECONDS);
        writer.shutdown();
        for (Review r : created) {
            awaitIndexed(r.id(), 1);
        }
        await().atMost(EVENTUALLY).until(() -> !indexer.isRebuilding() && status.snapshot().state() == IndexState.LIVE);
        assertThat(searchIds(word)).hasSize(30);
    }

    @Test
    void writeValidationAndErrorStatuses() {
        assertThat(mvc.post().uri("/api/reviews").contentType(MediaType.APPLICATION_JSON)
                .content("{\"parentAsin\":\"bad\",\"title\":\"\",\"body\":\"\",\"rating\":7}").exchange())
                .hasStatus(400).bodyJson().extractingPath("$.error.details").asArray().hasSize(3);
        assertThat(mvc.post().uri("/api/reviews").contentType(MediaType.APPLICATION_JSON).content("{broken").exchange())
                .hasStatus(400);
        Review review = create("errors", uniqueWord());
        assertThat(mvc.put().uri("/api/reviews/" + review.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"t\",\"body\":\"b\",\"rating\":3}").exchange()).hasStatus(400);
        assertThat(mvc.put().uri("/api/reviews/" + review.id()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":9,\"title\":\"t\",\"body\":\"b\",\"rating\":3}").exchange()).hasStatus(409);
        assertThat(mvc.delete().uri("/api/reviews/987654321?expectedVersion=1")).hasStatus(404);
        assertThat(mvc.post().uri("/api/reviews/987654321/helpful")).hasStatus(404);

        Review cleaned = reviews.create(new ReviewService.CreateCommand(ASIN, "nul\u0000 title", "a\r\nb", 3, null));
        assertThat(cleaned.title()).isEqualTo("nul title");
        assertThat(cleaned.body()).isEqualTo("a\nb");
    }

    // ------------------------------------------------------------------ helpers

    private void send(long key, byte[] value) throws Exception {
        producer.send(new ProducerRecord<>(properties.kafka().topic(), Long.toString(key), value)).get(10, TimeUnit.SECONDS);
    }

    private ConsumerRecord<String, byte[]> findOnDeadLetterTopic(byte[] value) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.KAFKA.getBootstrapServers());
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(p)) {
            TopicPartition tp = new TopicPartition(properties.kafka().deadLetterTopic(), 0);
            consumer.assign(List.of(tp));
            consumer.seekToBeginning(List.of(tp));
            long deadline = System.currentTimeMillis() + EVENTUALLY.toMillis();
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, byte[]> r : consumer.poll(Duration.ofMillis(500))) {
                    if (java.util.Arrays.equals(r.value(), value)) {
                        return r;
                    }
                }
            }
        }
        throw new AssertionError("poison record not found on the dead-letter topic");
    }
}
