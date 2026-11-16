package com.reviewpulse.search;

/** The index cannot answer queries (never built, or the build failed). Mapped to HTTP 503. */
public class IndexUnavailableException extends RuntimeException {

    public IndexUnavailableException(String message) {
        super(message);
    }
}
