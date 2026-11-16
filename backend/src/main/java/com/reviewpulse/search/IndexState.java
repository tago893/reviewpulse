package com.reviewpulse.search;

/** Lifecycle of the search projection, reported in every search response and in health. */
public enum IndexState {
    /** Nothing built yet; searches return 503. */
    EMPTY,
    /** A full build from PostgreSQL is running. Searches use the previous index if one exists. */
    BUILDING,
    /** Serving and applying changes normally. */
    LIVE,
    /** Serving, but results may be stale or incomplete (see the status detail). */
    DEGRADED,
    /** The last build failed and there is no usable index. */
    FAILED;

    public boolean servesQueries() {
        return this == LIVE || this == DEGRADED || this == BUILDING;
    }
}
