package com.reviewpulse.api;

import java.util.List;

/**
 * Machine-readable error code plus a human-readable message. {@code details} lists field-level
 * validation problems when relevant. Messages never include stack traces or SQL.
 */
public record ApiError(String code, String message, List<String> details) {

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, List.of());
    }

    public static ApiError of(String code, String message, List<String> details) {
        return new ApiError(code, message, List.copyOf(details));
    }
}
