package com.reviewpulse.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

import com.reviewpulse.api.AdminTokenFilter;
import com.reviewpulse.observability.IndexerProgress;
import com.reviewpulse.search.IndexVerifier;
import com.reviewpulse.search.index.ApplyOutcome;
import com.reviewpulse.search.index.SearchIndex;
import com.reviewpulse.support.IntegrationTest;

@IntegrationTest
class ReplayIT {

    private static final Duration EVENTUALLY = Duration.ofSeconds(60);
    private static final int RECORDS = 25;
    private static final long BASE_ID = (1L << 62) + 9_000_000L;

    @Autowired
    MockMvcTester mvc;
    @Autowired
    ReplayService replay;
    @Autowired
    SearchIndex index;
    @Autowired
    IndexerProgress progress;
    @Autowired
    IndexVerifier verifier;

    /** A small synthetic replay file in the documented export format (real slices come from data-tools). */
    @BeforeAll
    static void writeReplayFile() throws Exception {
        Path dir = Path.of("target/it-replay");
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < RECORDS; i++) {
            lines.add("{\"id\":\"" + (BASE_ID + i) + "\",\"sourceKey\":\"" + "%064x".formatted(BASE_ID + i)
                    + "\",\"parentAsin\":\"B0REPLAY01\",\"asin\":\"B0REPLAY01\",\"title\":\"replayed " + i
                    + "\",\"body\":\"replaytoken arrival " + i + "\",\"rating\":" + (1 + i % 5)
                    + ",\"helpfulVotes\":0,\"verifiedPurchase\":true,\"reviewedAt\":\""
                    + Instant.parse("2019-01-01T00:00:00Z").plusSeconds(i * 3600L) + "\"}");
        }
        Files.write(dir.resolve("it-slice.jsonl"), lines, StandardCharsets.UTF_8);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult admin(String method, String uri, String body) {
        var req = switch (method) {
            case "POST" -> mvc.post().uri(uri);
            case "PUT" -> mvc.put().uri(uri);
            default -> mvc.get().uri(uri);
        };
        req = req.header(AdminTokenFilter.HEADER, IntegrationTest.ADMIN_TOKEN);
        if (body != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return req.exchange();
    }

    @Test
    void replaysRealFormatSliceWithInjectedDuplicatesAndOutOfOrderEvents() {
        long staleBefore = progress.view().outcomes().get(ApplyOutcome.STALE_IGNORED);
        assertThat(admin("GET", "/api/admin/replay/files", null)).hasStatusOk()
                .bodyJson().extractingPath("$.data").asArray().contains("it-slice");

        assertThat(admin("POST", "/api/admin/replay/start",
                "{\"file\":\"it-slice\",\"eventsPerSecond\":200,\"seed\":7,\"duplicateRate\":0.3,\"outOfOrderRate\":0.3}"))
                .hasStatusOk();
        await().atMost(EVENTUALLY).until(() -> "COMPLETED".equals(replay.status().state()));
        ReplayService.Status done = replay.status();
        assertThat(done.created()).isEqualTo(RECORDS);
        assertThat(done.failures()).isZero();
        assertThat(done.injectedDuplicates()).isPositive();
        assertThat(done.injectedOutOfOrder()).isPositive();

        for (int i = 0; i < RECORDS; i++) {
            long id = BASE_ID + i;
            await().atMost(EVENTUALLY).until(() -> index.read(idx -> idx.liveDocId(id)).isPresent());
        }
        long injected = done.injectedDuplicates() + 2 * done.injectedOutOfOrder();
        await().atMost(EVENTUALLY).until(() ->
                progress.view().outcomes().get(ApplyOutcome.STALE_IGNORED) - staleBefore >= injected);
        assertThat(mvc.get().uri("/api/search").param("q", "replaytoken").param("pageSize", "50"))
                .bodyJson().extractingPath("$.data.totalHits").asNumber().isEqualTo(RECORDS);
        await().atMost(EVENTUALLY).until(() -> verifier.verify().consistent());

        // Same seed + same file: the run is repeatable, and every record is now a duplicate in PostgreSQL.
        admin("POST", "/api/admin/replay/start", "{\"file\":\"it-slice\",\"eventsPerSecond\":500,\"seed\":7}");
        await().atMost(EVENTUALLY).until(() -> "COMPLETED".equals(replay.status().state()));
        assertThat(replay.status().skippedExisting()).isEqualTo(RECORDS);
        assertThat(replay.status().created()).isZero();
    }

    @Test
    void rejectsUnsafeFileNamesAndInvalidParameters() {
        for (String bad : List.of("../secrets", "C:\\\\windows", "it-slice.jsonl", "UPPER", "")) {
            assertThat(admin("POST", "/api/admin/replay/start", "{\"file\":\"" + bad + "\",\"eventsPerSecond\":10}"))
                    .as(bad).hasStatus(400);
        }
        assertThat(admin("POST", "/api/admin/replay/start", "{\"file\":\"it-slice\",\"eventsPerSecond\":0}")).hasStatus(400);
        assertThat(admin("POST", "/api/admin/replay/start", "{\"file\":\"it-slice\",\"duplicateRate\":0.9}")).hasStatus(400);
        assertThat(admin("PUT", "/api/admin/replay/rate", null)).hasStatus(400);
        assertThat(mvc.post().uri("/api/admin/replay/start")).as("admin token required").hasStatus(401);
    }
}
