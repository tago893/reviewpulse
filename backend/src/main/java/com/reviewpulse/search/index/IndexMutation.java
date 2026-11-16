package com.reviewpulse.search.index;

/** A change to apply to the index. Both kinds carry the review version they represent. */
public sealed interface IndexMutation permits IndexMutation.Upsert, IndexMutation.Delete {

    long reviewId();

    long version();

    record Upsert(IndexedReview review) implements IndexMutation {
        @Override
        public long reviewId() {
            return review.reviewId();
        }

        @Override
        public long version() {
            return review.version();
        }
    }

    record Delete(long reviewId, long version) implements IndexMutation {
    }
}
