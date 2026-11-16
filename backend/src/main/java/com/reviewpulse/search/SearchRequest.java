package com.reviewpulse.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.reviewpulse.api.RequestValidationException;
import com.reviewpulse.search.query.CursorCodec;
import com.reviewpulse.search.query.Operator;
import com.reviewpulse.search.query.SearchFilters;
import com.reviewpulse.search.query.SearchQuery;
import com.reviewpulse.search.ranking.SortOrder;

/**
 * Raw search parameters exactly as received over HTTP. {@link #validate} reports every problem at
 * once instead of failing on the first.
 */
public record SearchRequest(String q, String operator, Integer ratingMin, Integer ratingMax, String parentAsin,
                            Boolean verifiedPurchase, String sort, Integer pageSize, String cursor) {

    public static final int MAX_QUERY_CHARS = 256;
    private static final Pattern ASIN = Pattern.compile("^[A-Z0-9]{10}$");

    /** Validated, normalized parameters (terms not yet tokenized). */
    public record Valid(String q, Operator operator, SearchFilters filters, SortOrder sort, int pageSize, String cursor) {
    }

    public Valid validate(int defaultPageSize) {
        List<String> problems = new ArrayList<>();
        String query = q == null ? "" : q.strip();
        if (query.isEmpty()) {
            problems.add("q is required");
        } else if (query.length() > MAX_QUERY_CHARS) {
            problems.add("q must be at most " + MAX_QUERY_CHARS + " characters");
        }
        Operator op = parse(problems, () -> Operator.parse(operator));
        SortOrder order = parse(problems, () -> SortOrder.parse(sort));
        int size = pageSize == null ? defaultPageSize : pageSize;
        if (size < 1 || size > SearchQuery.MAX_PAGE_SIZE) {
            problems.add("pageSize must be between 1 and " + SearchQuery.MAX_PAGE_SIZE);
        }
        String asin = parentAsin == null || parentAsin.isBlank() ? null : parentAsin.strip();
        if (asin != null && !ASIN.matcher(asin).matches()) {
            problems.add("parentAsin must be 10 uppercase letters or digits");
        }
        if (cursor != null && cursor.length() > CursorCodec.MAX_CURSOR_LENGTH) {
            problems.add("cursor is too long");
        }
        SearchFilters filters = parse(problems, () -> new SearchFilters(ratingMin, ratingMax, asin, verifiedPurchase));
        if (!problems.isEmpty()) {
            throw new RequestValidationException(problems);
        }
        return new Valid(query, op, filters, order, size, cursor == null || cursor.isBlank() ? null : cursor);
    }

    private static <T> T parse(List<String> problems, java.util.function.Supplier<T> parser) {
        try {
            return parser.get();
        } catch (IllegalArgumentException e) {
            problems.add(e.getMessage());
            return null;
        }
    }
}
