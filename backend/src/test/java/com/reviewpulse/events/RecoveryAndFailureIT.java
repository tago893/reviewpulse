package com.reviewpulse.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.util.FileSystemUtils;
import org.testcontainers.containers.GenericContainer;

import com.jayway.jsonpath.JsonPath;
import com.reviewpulse.config.ReviewPulseProperties;
import com.reviewpulse.reviews.Review;
import com.reviewpulse.reviews.ReviewService;
import com.reviewpulse.search.IndexState;
import com.reviewpulse.search.IndexStatus;
import com.reviewpulse.search.IndexVerifier;
import com.reviewpulse.search.SnapshotStore;
import com.reviewpulse.search.index.InvertedIndex;
import com.reviewpulse.search.index.SearchIndex;
import com.reviewpulse.search.tokenizer.Tokenizer;
import com.reviewpulse.support.IntegrationTest;
import com.reviewpulse.support.TestcontainersConfiguration;

/**
 * Restart, crash and dependency-outage scenarios. "Crash" is simulated faithfully at the state level:
 * the in-memory index is discarded and the on-disk checkpoint is rolled back to an older one, which is
 * exactly what a process that died after that checkpoint would leave behind.
 */
@IntegrationTest
class RecoveryAndFailureIT {

    private static final Duration EVENTUALLY = Duration.ofSeconds(60);
    private static final String ASIN = "B0RECOVER1";

    @Autowired
    MockMvcTester mvc;
    @Autowired
    ReviewService reviews;
    @Autowired
    SearchIndex index;
    @Autowired
    IndexStatus status;
    @Autowired
    IndexerWorker indexer;
    @Autowired
    IndexCheckpoints checkpoints;
    @Autowired
    IndexVerifier verifier;
    @Autowired
    SnapshotStore snapshots;
    @Autowired
    ReviewPulseProperties properties;
    @Autowired
    DataSource dataSource;

    @AfterEach
    void leaveSystemHealthy() {
        unpause(TestcontainersConfiguration.KAFKA);
        unpause(TestcontainersConfiguration.POSTGRES);
        await().atMost(EVENTUALLY).until(() -> status.snapshot().state() == IndexState.LIVE && !indexer.isRebuilding());
    }

    private static String uniqueWord() {
        return "r" + Long.toString(System.nanoTime(), 36);
    }

    private Review create(String word) {
        return reviews.create(new ReviewService.CreateCommand(ASIN, "recovery " + word, "body " + word, 4, true));
    }

    private void awaitIndexed(long id, long version) {
        await().atMost(EVENTUALLY).until(() -> index.read(idx -> idx.appliedVersion(id)).orElse(0) >= version);
    }

    private List<String> searchIds(String q) {
        MvcTestResult r = mvc.get().uri("/api/search").param("q", q).exchange();
        assertThat(r).hasStatusOk();
        try {
            return JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), "$.data.results[*].id");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void checkpointNow() {
        Instant before = Instant.now();
        indexer.requestSnapshot();
        await().atMost(EVENTUALLY).until(() -> {
            Instant saved = checkpoints.status().lastSavedAt();
            return saved != null && !saved.isBefore(before) && !indexer.isSnapshotPending();
        });
    }

    private void restartIndexerWithLostMemory() {
        indexer.stop();
        index.replace(new InvertedIndex(Tokenizer.DEFAULT));
    }

    private void startAndAwaitLive() {
        indexer.start();
        await().atMost(EVENTUALLY).until(() -> status.snapshot().state() == IndexState.LIVE && !indexer.isRebuilding());
    }

    @Test
    void crashAfterCheckpointRecoversFromCheckpointAndReplaysLaterEvents() throws Exception {
        String a1 = uniqueWord();
        Review a = create(a1);
        awaitIndexed(a.id(), 1);
        checkpointNow();
        byte[] olderCheckpoint = Files.readAllBytes(snapshots.file());

        String a2 = uniqueWord();
        String b1 = uniqueWord();
        reviews.update(a.id(), new ReviewService.UpdateCommand(1L, "edited", "body " + a2, 5));
        Review b = create(b1);
        awaitIndexed(a.id(), 2);
        awaitIndexed(b.id(), 1);

        restartIndexerWithLostMemory();
        Files.write(snapshots.file(), olderCheckpoint); // the later checkpoint "never reached disk"
        startAndAwaitLive();

        assertThat(checkpoints.status().lastRestore()).startsWith("restored snapshot");
        awaitIndexed(a.id(), 2);
        awaitIndexed(b.id(), 1);
        assertThat(searchIds(a1)).isEmpty();
        assertThat(searchIds(a2)).containsExactly(Long.toString(a.id()));
        assertThat(searchIds(b1)).containsExactly(Long.toString(b.id()));
        await().atMost(EVENTUALLY).until(() -> verifier.verify().consistent());
    }

    @Test
    void corruptCheckpointIsRejectedAndIndexIsRebuiltFromPostgres() throws Exception {
        String word = uniqueWord();
        Review r = create(word);
        awaitIndexed(r.id(), 1);

        restartIndexerWithLostMemory();
        Files.write(snapshots.file(), "definitely not a snapshot".getBytes(StandardCharsets.UTF_8));
        startAndAwaitLive();

        assertThat(checkpoints.status().lastRestore()).startsWith("snapshot rejected");
        assertThat(status.snapshot().detail()).isNotBlank();
        assertThat(searchIds(word)).containsExactly(Long.toString(r.id()));
        assertThat(verifier.verify().consistent()).isTrue();
    }

    @Test
    void failingCheckpointIsReportedAndNeverAdvancesCommittedOffsets() throws Exception {
        Review first = create(uniqueWord());
        awaitIndexed(first.id(), 1);
        checkpointNow();
        Map<TopicPartition, OffsetAndMetadata> committedBefore = committedOffsets();

        Path dir = snapshots.file().getParent();
        FileSystemUtils.deleteRecursively(dir);
        Files.writeString(dir, "a file where the snapshot directory should be");
        try {
            Review second = create(uniqueWord());
            awaitIndexed(second.id(), 1);
            long failuresBefore = checkpoints.status().failures();
            indexer.requestSnapshot();
            await().atMost(EVENTUALLY).until(() -> checkpoints.status().failures() > failuresBefore);

            assertThat(checkpoints.healthy()).isFalse();
            assertThat(mvc.get().uri("/api/health")).hasStatusOk().bodyJson()
                    .extractingPath("$.data.status").isEqualTo("DEGRADED");
            assertThat(committedOffsets()).as("offsets only move with durable checkpoints").isEqualTo(committedBefore);
            assertThat(searchIds("recovery")).as("search unaffected").isNotEmpty();
        } finally {
            Files.delete(dir);
        }
        checkpointNow();
        assertThat(checkpoints.healthy()).isTrue();
        assertThat(committedOffsets()).isNotEqualTo(committedBefore);
    }

    @Test
    void kafkaOutagePausesIndexingButNotWritesOrSearchAndRecovers() {
        String existing = uniqueWord();
        Review before = create(existing);
        awaitIndexed(before.id(), 1);

        pause(TestcontainersConfiguration.KAFKA);
        String during = uniqueWord();
        MvcTestResult created = mvc.post().uri("/api/reviews").contentType("application/json")
                .content("{\"parentAsin\":\"" + ASIN + "\",\"title\":\"outage\",\"body\":\"" + during + "\",\"rating\":3}")
                .exchange();
        assertThat(created).as("writes still commit to PostgreSQL").hasStatus(201);

        await().atMost(EVENTUALLY).until(() -> status.snapshot().state() == IndexState.DEGRADED);
        assertThat(searchIds(existing)).as("search keeps serving the last index").containsExactly(Long.toString(before.id()));
        assertThat(searchIds(during)).isEmpty();
        assertThat(mvc.get().uri("/api/health")).hasStatusOk().bodyJson()
                .extractingPath("$.data.components.kafka.status").isEqualTo("DOWN");

        unpause(TestcontainersConfiguration.KAFKA);
        await().atMost(EVENTUALLY).untilAsserted(() -> assertThat(searchIds(during)).hasSize(1));
        await().atMost(EVENTUALLY).until(() -> status.snapshot().state() == IndexState.LIVE);
    }

    @Test
    void postgresOutageMakesHealthDownAndSearchFailClearlyThenRecovers() {
        String word = uniqueWord();
        Review r = create(word);
        awaitIndexed(r.id(), 1);

        pause(TestcontainersConfiguration.POSTGRES);
        assertThat(mvc.get().uri("/api/health")).hasStatus(503).bodyJson()
                .extractingPath("$.data.components.database.status").isEqualTo("DOWN");
        assertThat(mvc.get().uri("/api/search").param("q", word)).hasStatus(503).bodyJson()
                .extractingPath("$.error.code").isEqualTo("DATABASE_UNAVAILABLE");

        unpause(TestcontainersConfiguration.POSTGRES);
        // docker pause can leave pooled TCP connections black-holed behind Docker Desktop's port proxy
        // (a real restart closes them instead). Recycle the pool so later tests start clean.
        ((HikariDataSource) dataSource).getHikariPoolMXBean().softEvictConnections();
        await().atMost(EVENTUALLY).untilAsserted(() -> assertThat(mvc.get().uri("/api/health")).hasStatusOk());
        assertThat(searchIds(word)).containsExactly(Long.toString(r.id()));
    }

    // ------------------------------------------------------------------ helpers

    private Map<TopicPartition, OffsetAndMetadata> committedOffsets() throws Exception {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, TestcontainersConfiguration.KAFKA.getBootstrapServers());
        try (Admin admin = Admin.create(p)) {
            return admin.listConsumerGroupOffsets(properties.kafka().consumerGroup())
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
        }
    }

    private static void pause(GenericContainer<?> container) {
        container.getDockerClient().pauseContainerCmd(container.getContainerId()).exec();
    }

    private static void unpause(GenericContainer<?> container) {
        var state = container.getDockerClient().inspectContainerCmd(container.getContainerId()).exec().getState();
        if (Boolean.TRUE.equals(state.getPaused())) {
            container.getDockerClient().unpauseContainerCmd(container.getContainerId()).exec();
        }
    }
}
