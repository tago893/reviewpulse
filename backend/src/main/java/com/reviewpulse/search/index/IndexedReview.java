package com.reviewpulse.search.index;

import java.util.Objects;

/**
 * The searchable projection of one review version, as handed to the index.
 * Full text is used to build postings but is not retained by the index.
 */
public record IndexedReview(
        long reviewId,
        long version,
        String parentAsin,
        String title,
        String body,
        int rating,
        int helpfulVotes,
        boolean verifiedPurchase,
        long reviewedAtEpochMillis) {

    public IndexedReview {
        Objects.requireNonNull(parentAsin, "parentAsin");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(body, "body");
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        if (rating < 1 || rating > 5) {
            throw new IllegalArgumentException("rating must be 1..5");
        }
        if (helpfulVotes < 0) {
            throw new IllegalArgumentException("helpfulVotes must be >= 0");
        }
    }
}
