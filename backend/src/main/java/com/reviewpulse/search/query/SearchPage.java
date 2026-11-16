package com.reviewpulse.search.query;

import java.util.List;

/**
 * One page of ranked hits from the index.
 *
 * @param hits      ranked hits, best first
 * @param totalHits all documents matching the query and filters (independent of cursor/page size)
 * @param hasMore   true if more hits exist after the last one on this page
 */
public record SearchPage(List<Hit> hits, long totalHits, boolean hasMore) {

    public static final SearchPage EMPTY = new SearchPage(List.of(), 0, false);

    public SearchPage {
        hits = List.copyOf(hits);
    }
}
