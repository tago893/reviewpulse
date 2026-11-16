package com.reviewpulse.search.query;

/**
 * One ranked match. The non-docId fields form the sort key used by cursors.
 * {@code score} is the BM25F relevance score; it is computed for every returned hit, whatever the sort.
 */
public record Hit(int docId, long reviewId, double score, long reviewedAt, int helpfulVotes, long version) {

    public SortKey sortKey() {
        return new SortKey(score, reviewedAt, helpfulVotes, reviewId);
    }
}
