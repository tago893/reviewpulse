package com.reviewpulse.api;

/** The request conflicts with current state (version mismatch, operation already running). HTTP 409. */
public class ConflictException extends RuntimeException {

    private final String code;

    public ConflictException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
