package com.reviewpulse.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.reviewpulse.reviews.Review;
import com.reviewpulse.search.index.IndexMutation;
import com.reviewpulse.search.index.IndexedReview;

/**
 * A "fat" event: the complete searchable state of a review at {@code version}
 * ({@code state} is null for deletes).
 *
 * <p>Carrying full state instead of a diff is what makes out-of-order delivery safe: if version 5
 * arrives before version 4, applying 5 and later ignoring 4 still yields the correct final state.
 */
public record ReviewEvent(UUID eventId, EventType type, long reviewId, long version, Instant occurredAt, State state) {

    public static final int SCHEMA_VERSION = 1;

    /** Searchable review fields at the event's version. */
    public record State(String parentAsin, String title, String body, int rating, int helpfulVotes,
                        boolean verifiedPurchase, Instant reviewedAt) {
    }

    public ReviewEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if ((type == EventType.REVIEW_DELETED) != (state == null)) {
            throw new IllegalArgumentException("deletes carry no state; every other event must");
        }
    }

    public static ReviewEvent of(EventType type, Review review, Instant occurredAt) {
        State state = type == EventType.REVIEW_DELETED ? null
                : new State(review.parentAsin(), review.title(), review.body(), review.rating(),
                        review.helpfulVotes(), review.verifiedPurchase(), review.reviewedAt());
        return new ReviewEvent(UUID.randomUUID(), type, review.id(), review.version(), occurredAt, state);
    }

    public IndexMutation toMutation() {
        if (state == null) {
            return new IndexMutation.Delete(reviewId, version);
        }
        return new IndexMutation.Upsert(new IndexedReview(reviewId, version, state.parentAsin(), state.title(),
                state.body(), state.rating(), state.helpfulVotes(), state.verifiedPurchase(),
                state.reviewedAt().toEpochMilli()));
    }
}
