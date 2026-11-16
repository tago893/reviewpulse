package com.reviewpulse.caching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.jayway.jsonpath.JsonPath;
import com.reviewpulse.events.IndexerWorker;
import com.reviewpulse.search.IndexState;
import com.reviewpulse.search.IndexStatus;
import com.reviewpulse.reviews.Review;
import com.reviewpulse.reviews.ReviewService;
import com.reviewpulse.search.index.SearchIndex;
import com.reviewpulse.support.IntegrationTest;
import com.reviewpulse.support.TestcontainersConfiguration;

@IntegrationTest
class CacheIT {

    private static final Duration EVENTUALLY = Duration.ofSeconds(30);

    @Autowired
    MockMvcTester mvc;
    @Autowired
    ReviewService reviews;
    @Autowired
    SearchIndex index;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    IndexerWorker indexer;
    @Autowired
    IndexStatus status;

    /**
     * The first Redis command after startup can exceed the 150 ms timeout while Lettuce connects, which
     * (by design) opens the bypass circuit briefly. Wait until lookups reach Redis, using a probe query
     * whose cache key no test uses.
     */
    @BeforeEach
    void awaitCacheReachable() {
        String probe = "probe" + word();
        await().atMost(EVENTUALLY).until(() -> List.of("HIT", "MISS").contains(path(search(probe), "$.meta.cache")));
    }

    @AfterEach
    void unpauseRedis() {
        var docker = TestcontainersConfiguration.REDIS.getDockerClient();
        String id = TestcontainersConfiguration.REDIS.getContainerId();
        if (Boolean.TRUE.equals(docker.inspectContainerCmd(id).exec().getState().getPaused())) {
            docker.unpauseContainerCmd(id).exec();
        }
    }

    private Review indexed(String word) {
        Review r = reviews.create(new ReviewService.CreateCommand("B0CACHE001", "cache " + word, word, 4, true));
        await().atMost(EVENTUALLY).until(() -> index.read(idx -> idx.appliedVersion(r.id())).orElse(0) >= 1);
        return r;
    }

    private static String word() {
        return "c" + Long.toString(System.nanoTime(), 36);
    }

    private MvcTestResult search(String q) {
        MvcTestResult r = mvc.get().uri("/api/search").param("q", q).exchange();
        assertThat(r).hasStatusOk();
        return r;
    }

    private static <T> T path(MvcTestResult r, String jsonPath) {
        try {
            return JsonPath.read(r.getResponse().getContentAsString(StandardCharsets.UTF_8), jsonPath);
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void repeatedQueryIsServedFromCacheWithIdenticalResults() {
        String w = word();
        Review r = indexed(w);
        MvcTestResult first = search(w);
        MvcTestResult second = search(w);
        assertThat((String) path(first, "$.meta.cache")).isEqualTo("MISS");
        assertThat((String) path(second, "$.meta.cache")).isEqualTo("HIT");
        assertThat((List<String>) path(second, "$.data.results[*].id")).containsExactly(Long.toString(r.id()))
                .isEqualTo(path(first, "$.data.results[*].id"));
    }

    @Test
    void anyIndexChangeInvalidatesCachedPages() {
        String w = word();
        Review r = indexed(w);
        search(w);
        assertThat((String) path(search(w), "$.meta.cache")).isEqualTo("HIT");

        String replacement = word();
        reviews.update(r.id(), new ReviewService.UpdateCommand(1L, "cache", replacement, 4));
        await().atMost(EVENTUALLY).until(() -> index.read(idx -> idx.appliedVersion(r.id())).orElse(0) >= 2);

        MvcTestResult after = search(w);
        assertThat((String) path(after, "$.meta.cache")).isEqualTo("MISS");
        assertThat((List<String>) path(after, "$.data.results")).isEmpty();
    }

    @Test
    void reviewDeletedAfterCachingIsNeverShownFromCache() {
        String w = word();
        Review r = indexed(w);
        search(w);
        // Delete behind the index's back: generation is unchanged, so the cached hit-list is reused...
        jdbc.sql("UPDATE reviews SET deleted = TRUE, deleted_at = now(), version = version + 1 WHERE id = :id")
                .param("id", r.id()).update();
        try {
            MvcTestResult cached = search(w);
            assertThat((String) path(cached, "$.meta.cache")).isEqualTo("HIT");
            // ...but hydration from PostgreSQL drops the deleted review.
            assertThat((List<String>) path(cached, "$.data.results")).isEmpty();
            assertThat((Integer) path(cached, "$.meta.staleHitsDropped")).isEqualTo(1);
        } finally {
            resyncIndexWithDatabase();
        }
    }

    /**
     * This test deliberately desynchronizes the index from PostgreSQL (a delete with no event). Rebuild
     * afterwards so later test classes, which check index/database consistency, start from a clean state.
     */
    private void resyncIndexWithDatabase() {
        await().atMost(EVENTUALLY).until(() -> indexer.requestRebuild("test cleanup: resync after direct DB edit"));
        await().atMost(EVENTUALLY).until(() -> !indexer.isRebuilding() && status.snapshot().state() == IndexState.LIVE);
    }

    @Test
    void searchKeepsWorkingQuicklyWhileRedisIsDown() {
        String w = word();
        Review r = indexed(w);
        TestcontainersConfiguration.REDIS.getDockerClient()
                .pauseContainerCmd(TestcontainersConfiguration.REDIS.getContainerId()).exec();
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            MvcTestResult res = search(w);
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertThat((List<String>) path(res, "$.data.results[*].id")).containsExactly(Long.toString(r.id()));
            assertThat((String) path(res, "$.meta.cache")).isIn("ERROR", "BYPASSED");
            assertThat(millis).as("Redis timeouts must stay bounded").isLessThan(2_000);
        }
        unpauseRedis();
        await().atMost(EVENTUALLY).until(() -> List.of("HIT", "MISS").contains(path(search(w), "$.meta.cache")));
    }
}
