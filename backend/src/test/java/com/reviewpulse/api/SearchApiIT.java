package com.reviewpulse.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

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
import com.reviewpulse.search.SearchFixtures;
import com.reviewpulse.search.index.IndexedReview;
import com.reviewpulse.support.IntegrationTest;

@IntegrationTest
class SearchApiIT {

    @Autowired
    MockMvcTester mvc;
    @Autowired
    JdbcClient jdbc;
    @Autowired
    IndexerWorker manager;
    @Autowired
    IndexStatus status;

    @BeforeEach
    void loadLabeledCorpusAndBuildIndex() {
        // Let events written by other test classes drain first, so none lands after the rebuild below.
        await().atMost(Duration.ofSeconds(30)).until(() -> jdbc.sql(
                "SELECT count(*) FROM outbox_events WHERE status = 'PENDING'").query(Long.class).single() == 0);
        jdbc.sql("DELETE FROM reviews").update();
        for (IndexedReview r : SearchFixtures.labeledCorpus()) {
            jdbc.sql("""
                    INSERT INTO reviews (id, source, parent_asin, title, body, rating, helpful_votes,
                                         verified_purchase, reviewed_at, version)
                    VALUES (:id, 'API', :asin, :title, :body, :rating, :helpful, :verified, :at, :version)
                    """)
                    .param("id", r.reviewId()).param("asin", r.parentAsin()).param("title", r.title())
                    .param("body", r.body()).param("rating", r.rating()).param("helpful", r.helpfulVotes())
                    .param("verified", r.verifiedPurchase())
                    .param("at", Timestamp.from(Instant.ofEpochMilli(1_600_000_000_000L + r.reviewedAtEpochMillis())))
                    .param("version", r.version())
                    .update();
        }
        // Fixture rows use explicit ids in the API range; move the sequence past them so API creates
        // in other tests never collide with them.
        jdbc.sql("SELECT setval('review_api_id_seq', GREATEST(:floor, (SELECT last_value FROM review_api_id_seq)))")
                .param("floor", 1_000L).query(Long.class).single();
        rebuildAndWait();
    }

    private void rebuildAndWait() {
        await().atMost(Duration.ofSeconds(30)).until(() -> manager.requestRebuild("test"));
        await().atMost(Duration.ofSeconds(30)).until(() -> !manager.isRebuilding()
                && status.snapshot().state() == IndexState.LIVE);
    }

    private MvcTestResult search(String... params) {
        var request = mvc.get().uri("/api/search");
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        return request.exchange();
    }

    @Test
    void returnsRankedHydratedHighlightedResults() {
        MvcTestResult result = search("q", "dry skin");
        assertThat(result).hasStatusOk().headers().hasValue("X-Index-State", "LIVE");
        assertThat(result).bodyJson().extractingPath("$.data.results[*].id").asArray().containsExactly("1", "9");
        assertThat(result).bodyJson().extractingPath("$.data.totalHits").asNumber().isEqualTo(2);
        assertThat(result).bodyJson().extractingPath("$.data.query.terms").asArray().containsExactly("dry", "skin");
        assertThat(result).bodyJson().extractingPath("$.data.results[0].title.text").asString().isEqualTo("Great for dry skin");
        assertThat(result).bodyJson().extractingPath("$.data.results[0].title.highlights[0].start").asNumber().isEqualTo(10);
        assertThat(result).bodyJson().extractingPath("$.meta.staleHitsDropped").asNumber().isEqualTo(0);
    }

    @Test
    void filtersSortsAndCursorPaginationWorkEndToEnd() throws Exception {
        MvcTestResult first = search("q", "skin", "operator", "OR", "sort", "recency", "pageSize", "4");
        assertThat(first).bodyJson().extractingPath("$.data.results[*].id").asArray().containsExactly("9", "8", "7", "5");
        String cursor = JsonPath.read(first.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                "$.data.nextCursor");
        MvcTestResult second = search("q", "skin", "operator", "OR", "sort", "recency", "pageSize", "4", "cursor", cursor);
        assertThat(second).bodyJson().extractingPath("$.data.results[*].id").asArray().containsExactly("2", "1");
        assertThat(second).bodyJson().extractingPath("$.data.hasMore").asBoolean().isFalse();

        assertThat(search("q", "skin", "ratingMin", "5", "verifiedPurchase", "true"))
                .bodyJson().extractingPath("$.data.results[*].id").asArray().containsExactlyInAnyOrder("1", "5", "9");
        assertThat(search("q", "skin", "parentAsin", SearchFixtures.P2))
                .bodyJson().extractingPath("$.data.results[*].id").asArray().containsExactly("8");

        assertThat(search("q", "skin", "sort", "relevance", "cursor", cursor)).hasStatus(400)
                .bodyJson().extractingPath("$.error.code").isEqualTo("INVALID_CURSOR");
    }

    @Test
    void rejectsInvalidParametersWithAllProblemsListed() {
        MvcTestResult result = search("q", "skin", "pageSize", "0", "ratingMin", "9", "operator", "XOR", "sort", "best");
        assertThat(result).hasStatus(400).bodyJson().extractingPath("$.error.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.error.details").asArray().hasSize(4);
        assertThat(search()).hasStatus(400);
        assertThat(search("q", "x".repeat(257))).hasStatus(400);
        assertThat(search("q", "skin", "ratingMin", "abc")).hasStatus(400);
        assertThat(search("q", "a b c d e f g h k l m n")).hasStatus(400);
    }

    @Test
    void stopWordOnlyQueryReturnsEmptyResultWithWarning() {
        MvcTestResult result = search("q", "the and of");
        assertThat(result).hasStatusOk().bodyJson().extractingPath("$.data.totalHits").asNumber().isEqualTo(0);
        assertThat(result).bodyJson().extractingPath("$.meta.warning").asString().contains("no searchable terms");
    }

    @Test
    void reviewDeletedInDatabaseButNotYetInIndexIsNeverShown() {
        jdbc.sql("UPDATE reviews SET deleted = TRUE, deleted_at = now(), version = version + 1 WHERE id = 9").update();
        try {
            MvcTestResult result = search("q", "dry skin");
            assertThat(result).bodyJson().extractingPath("$.data.results[*].id").asArray().containsExactly("1");
            assertThat(result).bodyJson().extractingPath("$.meta.staleHitsDropped").asNumber().isEqualTo(1);
            assertThat(mvc.get().uri("/api/reviews/9")).hasStatus(404);
        } finally {
            // The delete above bypassed the event pipeline on purpose; resync so later test classes see a
            // consistent index.
            rebuildAndWait();
        }
    }

    @Test
    void getReviewReportsIndexFreshness() {
        assertThat(mvc.get().uri("/api/reviews/5")).hasStatusOk().bodyJson()
                .extractingPath("$.data.searchable").asBoolean().isTrue();
        assertThat(mvc.get().uri("/api/reviews/abc")).hasStatus(400);
        assertThat(mvc.get().uri("/api/reviews/424242")).hasStatus(404);
    }

    @Test
    void adminEndpointsRequireToken() {
        assertThat(mvc.get().uri("/api/admin/index-status")).hasStatus(401);
        assertThat(mvc.get().uri("/api/admin/index-status").header(AdminTokenFilter.HEADER, "wrong")).hasStatus(401);
        assertThat(mvc.get().uri("/api/admin/index-status").header(AdminTokenFilter.HEADER, IntegrationTest.ADMIN_TOKEN))
                .hasStatusOk().bodyJson().extractingPath("$.data.liveDocs").asNumber().isEqualTo(10);
    }
}
