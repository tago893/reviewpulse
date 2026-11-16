package com.reviewpulse.search.query;

/** Position in a sort order; a cursor points just after this key. */
public record SortKey(double score, long reviewedAt, int helpfulVotes, long reviewId) {
}
