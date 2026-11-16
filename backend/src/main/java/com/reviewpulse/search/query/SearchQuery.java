package com.reviewpulse.search.query;

import java.util.List;
import java.util.Objects;

import com.reviewpulse.search.ranking.SortOrder;

/**
 * A fully parsed and validated query.
 *
 * @param terms    distinct normalized terms, in query order
 * @param after    keyset cursor position, or null for the first page
 */
public record SearchQuery(List<String> terms, Operator operator, SearchFilters filters, SortOrder sort,
                          int pageSize, SortKey after) {

    public static final int MAX_TERMS = 10;
    public static final int MAX_PAGE_SIZE = 50;

    public SearchQuery {
        terms = List.copyOf(terms);
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(filters, "filters");
        Objects.requireNonNull(sort, "sort");
        if (terms.size() > MAX_TERMS) {
            throw new IllegalArgumentException("at most " + MAX_TERMS + " distinct search terms are allowed");
        }
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
    }

    /** Identity of the result ordering, excluding page size and cursor position. */
    public String fingerprint() {
        return "terms=" + String.join(" ", terms) + ";op=" + operator + ";sort=" + sort + ";" + filters.canonical();
    }

    public SearchQuery withAfter(SortKey key) {
        return new SearchQuery(terms, operator, filters, sort, pageSize, key);
    }
}
