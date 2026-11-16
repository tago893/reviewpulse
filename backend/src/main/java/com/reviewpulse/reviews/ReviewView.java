package com.reviewpulse.reviews;

import java.time.Instant;

/**
 * Public representation of a review.
 *
 * @param indexedVersion version currently in the search index, or null if not indexed
 * @param searchable     true when the index reflects exactly this version
 */
public record ReviewView(String id, String source, String parentAsin, String asin, String title, String body,
                         int rating, int helpfulVotes, boolean verifiedPurchase, Instant reviewedAt,
                         Instant createdAt, Instant updatedAt, long version, Long indexedVersion, boolean searchable) {

    public static ReviewView of(Review r, Long indexedVersion, boolean searchable) {
        return new ReviewView(Long.toString(r.id()), r.source(), r.parentAsin(), r.asin(), r.title(), r.body(),
                r.rating(), r.helpfulVotes(), r.verifiedPurchase(), r.reviewedAt(), r.createdAt(), r.updatedAt(),
                r.version(), indexedVersion, searchable);
    }
}
