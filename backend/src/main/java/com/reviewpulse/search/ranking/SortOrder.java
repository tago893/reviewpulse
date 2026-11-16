package com.reviewpulse.search.ranking;

import java.util.Locale;

/**
 * Business sort orders. Each defines a TOTAL order (the review id is the final tie-breaker), which
 * is what makes keyset pagination deterministic. Relevance scoring is computed independently of the
 * chosen order; RECENCY never needs a score to decide membership in the top-K.
 */
public enum SortOrder {
    /** BM25 score desc, review id asc. */
    RELEVANCE,
    /** Original review timestamp desc, review id asc. */
    RECENCY,
    /** Helpful votes desc, BM25 score desc, review id asc. */
    HELPFULNESS;

    public boolean usesScore() {
        return this != RECENCY;
    }

    /**
     * Compares two sort keys. Negative means {@code a} ranks before (is better than) {@code b}.
     */
    public int compare(double scoreA, long timeA, int helpfulA, long idA,
                       double scoreB, long timeB, int helpfulB, long idB) {
        int c = switch (this) {
            case RELEVANCE -> Double.compare(scoreB, scoreA);
            case RECENCY -> Long.compare(timeB, timeA);
            case HELPFULNESS -> {
                int h = Integer.compare(helpfulB, helpfulA);
                yield h != 0 ? h : Double.compare(scoreB, scoreA);
            }
        };
        return c != 0 ? c : Long.compare(idA, idB);
    }

    public static SortOrder parse(String value) {
        if (value == null || value.isBlank()) {
            return RELEVANCE;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("sort must be one of relevance, recency, helpfulness");
        }
    }
}
