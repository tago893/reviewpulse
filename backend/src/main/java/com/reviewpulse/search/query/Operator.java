package com.reviewpulse.search.query;

import java.util.Locale;

/** How multiple query terms combine. */
public enum Operator {
    /** Every term must appear (title or body). */
    AND,
    /** At least one term must appear; documents matching more terms score higher. */
    OR;

    public static Operator parse(String value) {
        if (value == null || value.isBlank()) {
            return AND;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("operator must be AND or OR");
        }
    }
}
