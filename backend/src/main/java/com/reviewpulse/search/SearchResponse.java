package com.reviewpulse.search;

import java.time.Instant;
import java.util.List;

import com.reviewpulse.search.highlight.Highlighter.HighlightedText;

/**
 * Search API payload. Review ids are strings: dataset ids exceed 2^53 and would lose precision as
 * JavaScript numbers.
 */
public record SearchResponse(
        QueryEcho query,
        long totalHits,
        List<Result> results,
        String nextCursor,
        boolean hasMore) {

    public SearchResponse {
        results = List.copyOf(results);
    }

    /** What the server actually searched for after tokenization. */
    public record QueryEcho(String q, List<String> terms, String operator, String sort, Integer ratingMin,
                            Integer ratingMax, String parentAsin, Boolean verifiedPurchase, int pageSize) {
        public QueryEcho {
            terms = List.copyOf(terms);
        }
    }

    /**
     * One hit, hydrated from PostgreSQL.
     *
     * @param indexedVersion review version the index ranked; may trail {@code version} briefly
     */
    public record Result(String id, String parentAsin, int rating, int helpfulVotes, boolean verifiedPurchase,
                         Instant reviewedAt, long version, long indexedVersion, double score,
                         HighlightedText title, HighlightedText snippet) {
    }
}
