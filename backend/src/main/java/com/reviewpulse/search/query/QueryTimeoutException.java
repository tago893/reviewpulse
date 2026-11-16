package com.reviewpulse.search.query;

/** Thrown when a query exceeds its CPU-time budget; the caller gets an error, never partial results. */
public class QueryTimeoutException extends RuntimeException {

    public QueryTimeoutException(long budgetMillis) {
        super("query exceeded its time budget of " + budgetMillis + " ms");
    }
}
