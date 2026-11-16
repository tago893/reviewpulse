package com.reviewpulse.search.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

import com.reviewpulse.search.ranking.SortOrder;

/**
 * Bounded top-K selection over a stream of matches using a size-K heap whose head is the WORST
 * retained hit. O(M log K) time and O(K) memory for M matches, regardless of page depth, because
 * the keyset cursor filters out everything at or before the previous page instead of skipping N rows.
 */
final class TopKCollector {

    private final SortOrder sort;
    private final int capacity;
    private final SortKey after;
    private final PriorityQueue<Hit> heap;
    private long totalMatches;
    private long matchesAfterCursor;

    TopKCollector(SortOrder sort, int capacity, SortKey after) {
        this.sort = sort;
        this.capacity = capacity;
        this.after = after;
        Comparator<Hit> betterFirst = (a, b) -> compare(a, b.score(), b.reviewedAt(), b.helpfulVotes(), b.reviewId());
        this.heap = new PriorityQueue<>(capacity + 1, betterFirst.reversed());
    }

    /**
     * Counts a match and reports whether it would enter the current top-K. Callers compute the
     * (possibly expensive) score lazily for sort orders that do not need it to decide.
     */
    boolean wouldAccept(long reviewId, double score, long reviewedAt, int helpful) {
        totalMatches++;
        if (after != null && sort.compare(score, reviewedAt, helpful, reviewId,
                after.score(), after.reviewedAt(), after.helpfulVotes(), after.reviewId()) <= 0) {
            return false;
        }
        matchesAfterCursor++;
        if (heap.size() < capacity) {
            return true;
        }
        Hit worst = heap.peek();
        return sort.compare(score, reviewedAt, helpful, reviewId,
                worst.score(), worst.reviewedAt(), worst.helpfulVotes(), worst.reviewId()) < 0;
    }

    /** Adds a hit previously approved by {@link #wouldAccept}. */
    void add(Hit hit) {
        heap.add(hit);
        if (heap.size() > capacity) {
            heap.poll();
        }
    }

    SearchPage page() {
        List<Hit> hits = new ArrayList<>(heap);
        hits.sort((a, b) -> compare(a, b.score(), b.reviewedAt(), b.helpfulVotes(), b.reviewId()));
        return new SearchPage(hits, totalMatches, matchesAfterCursor > capacity);
    }

    private int compare(Hit a, double score, long reviewedAt, int helpful, long reviewId) {
        return sort.compare(a.score(), a.reviewedAt(), a.helpfulVotes(), a.reviewId(), score, reviewedAt, helpful, reviewId);
    }
}
