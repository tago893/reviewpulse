package com.reviewpulse.search.query;

import com.reviewpulse.search.index.InvertedIndex;

/**
 * Structured filters. A null component means "no constraint".
 *
 * @param ratingMin        inclusive lower bound, 1..5
 * @param ratingMax        inclusive upper bound, 1..5
 * @param parentAsin       restrict to one product
 * @param verifiedPurchase restrict to verified (true) or unverified (false) purchases
 */
public record SearchFilters(Integer ratingMin, Integer ratingMax, String parentAsin, Boolean verifiedPurchase) {

    public static final SearchFilters NONE = new SearchFilters(null, null, null, null);

    public SearchFilters {
        if (ratingMin != null && (ratingMin < 1 || ratingMin > 5)) {
            throw new IllegalArgumentException("ratingMin must be between 1 and 5");
        }
        if (ratingMax != null && (ratingMax < 1 || ratingMax > 5)) {
            throw new IllegalArgumentException("ratingMax must be between 1 and 5");
        }
        if (ratingMin != null && ratingMax != null && ratingMin > ratingMax) {
            throw new IllegalArgumentException("ratingMin must not exceed ratingMax");
        }
    }

    /** Doc-table filters (the product filter is applied by posting-list intersection instead). */
    boolean accepts(InvertedIndex index, int docId) {
        if (ratingMin != null || ratingMax != null) {
            int rating = index.rating(docId);
            if ((ratingMin != null && rating < ratingMin) || (ratingMax != null && rating > ratingMax)) {
                return false;
            }
        }
        return verifiedPurchase == null || index.verifiedPurchase(docId) == verifiedPurchase;
    }

    /** Stable textual form used in cursor fingerprints and cache keys. */
    public String canonical() {
        return "rmin=" + (ratingMin == null ? "" : ratingMin)
                + ";rmax=" + (ratingMax == null ? "" : ratingMax)
                + ";asin=" + (parentAsin == null ? "" : parentAsin)
                + ";verified=" + (verifiedPurchase == null ? "" : verifiedPurchase);
    }
}
