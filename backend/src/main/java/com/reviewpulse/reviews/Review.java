package com.reviewpulse.reviews;

import java.time.Instant;

import com.reviewpulse.search.index.IndexedReview;

/** Authoritative review state as stored in PostgreSQL. */
public record Review(
        long id,
        String source,
        String parentAsin,
        String asin,
        String title,
        String body,
        int rating,
        int helpfulVotes,
        boolean verifiedPurchase,
        Instant reviewedAt,
        Instant createdAt,
        Instant updatedAt,
        long version,
        boolean deleted) {

    public IndexedReview toIndexed() {
        return new IndexedReview(id, version, parentAsin, title, body, rating, helpfulVotes, verifiedPurchase,
                reviewedAt.toEpochMilli());
    }
}
