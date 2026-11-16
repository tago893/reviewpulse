package com.reviewpulse.caching;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

import com.reviewpulse.search.query.SearchPage;
import com.reviewpulse.search.query.SearchQuery;

/**
 * Cache of ranked index hit-lists (never of review content).
 *
 * <p>Keys embed the index generation, so any index change makes every older entry unreachable: a
 * cached page is only ever served for exactly the index state that produced it. Hits are hydrated
 * from PostgreSQL after a cache hit too, so a review deleted after caching is still never shown.
 */
public interface SearchResultCache {

    /** How a lookup went, reported in search response metadata. */
    enum Outcome { HIT, MISS, BYPASSED, DISABLED, ERROR }

    /** Lookup result. */
    record Lookup(Outcome outcome, Optional<SearchPage> page) {
    }

    Lookup get(String key);

    void put(String key, SearchPage page);

    /** Counters for metrics. */
    record Stats(boolean enabled, long hits, long misses, long errors, long bypassed, boolean circuitOpen) {
        public double hitRate() {
            long lookups = hits + misses;
            return lookups == 0 ? 0.0 : (double) hits / lookups;
        }
    }

    Stats stats();

    static String key(SearchQuery query, long generation) {
        String after = query.after() == null ? "first"
                : Double.doubleToRawLongBits(query.after().score()) + "," + query.after().reviewedAt() + ","
                + query.after().helpfulVotes() + "," + query.after().reviewId();
        String material = query.fingerprint() + "|size=" + query.pageSize() + "|after=" + after;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return "rp:search:v1:" + generation + ":" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
