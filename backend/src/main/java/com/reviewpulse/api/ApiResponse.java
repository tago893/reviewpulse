package com.reviewpulse.api;

import java.util.Map;

/**
 * Uniform response envelope for every /api endpoint.
 *
 * @param success true when the request was fulfilled
 * @param data    payload, null on error
 * @param error   error details, null on success
 * @param meta    optional metadata (pagination, index state, timings)
 */
public record ApiResponse<T>(boolean success, T data, ApiError error, Map<String, Object> meta) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Map.of());
    }

    public static <T> ApiResponse<T> ok(T data, Map<String, Object> meta) {
        return new ApiResponse<>(true, data, null, Map.copyOf(meta));
    }

    public static <T> ApiResponse<T> failure(ApiError error) {
        return new ApiResponse<>(false, null, error, Map.of());
    }
}
