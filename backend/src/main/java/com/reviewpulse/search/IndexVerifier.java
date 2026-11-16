package com.reviewpulse.search;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import com.reviewpulse.search.index.SearchIndex;

/**
 * Compares the index with PostgreSQL. Because indexing is asynchronous, a small number of differences
 * can be in-flight changes; the report says how many and lists samples so an operator can re-check.
 * Differences that persist across runs indicate a stale or incomplete index (fix: POST /api/admin/reindex).
 */
@Component
public class IndexVerifier {

    private static final int SAMPLES = 10;

    private final SearchIndex index;
    private final JdbcTemplate jdbc;

    public IndexVerifier(SearchIndex index, JdbcTemplate jdbc) {
        this.index = index;
        this.jdbc = jdbc;
    }

    /** Result of one comparison. */
    public record Report(long databaseLive, long indexLive, long missingFromIndex, long extraInIndex,
                         long indexBehind, long indexAhead, List<String> samples, boolean consistent) {
        public Report {
            samples = List.copyOf(samples);
        }
    }

    public Report verify() {
        Map<Long, Long> indexed = index.read(idx -> idx.liveReviewVersions());
        Map<Long, Long> database = new HashMap<>(indexed.size() * 2);
        jdbc.query("SELECT id, version FROM reviews WHERE NOT deleted",
                (RowCallbackHandler) rs -> database.put(rs.getLong(1), rs.getLong(2)));
        long missing = 0;
        long behind = 0;
        long ahead = 0;
        List<String> samples = new ArrayList<>();
        for (Map.Entry<Long, Long> e : database.entrySet()) {
            Long v = indexed.get(e.getKey());
            if (v == null) {
                missing++;
                sample(samples, "missing " + e.getKey());
            } else if (v < e.getValue()) {
                behind++;
                sample(samples, "behind " + e.getKey() + " (index v" + v + ", db v" + e.getValue() + ")");
            } else if (v > e.getValue()) {
                ahead++;
                sample(samples, "ahead " + e.getKey() + " (index v" + v + ", db v" + e.getValue() + ")");
            }
        }
        long extra = 0;
        for (Long id : indexed.keySet()) {
            if (!database.containsKey(id)) {
                extra++;
                sample(samples, "extra " + id);
            }
        }
        boolean consistent = missing == 0 && extra == 0 && behind == 0 && ahead == 0;
        return new Report(database.size(), indexed.size(), missing, extra, behind, ahead, samples, consistent);
    }

    private static void sample(List<String> samples, String s) {
        if (samples.size() < SAMPLES) {
            samples.add(s);
        }
    }
}
