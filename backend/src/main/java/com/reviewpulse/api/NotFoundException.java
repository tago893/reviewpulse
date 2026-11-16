package com.reviewpulse.api;

/** Requested resource does not exist (or is deleted). Mapped to HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
