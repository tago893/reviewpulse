package com.reviewpulse.api;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.reviewpulse.search.IndexUnavailableException;
import com.reviewpulse.search.query.CursorCodec;
import com.reviewpulse.search.query.QueryTimeoutException;

/**
 * Maps exceptions to the API envelope. Client errors carry a precise message; server errors carry a
 * generic message (details go to the log only, never to the client).
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(RequestValidationException.class)
    ResponseEntity<ApiResponse<Void>> validation(RequestValidationException e) {
        return error(HttpStatus.BAD_REQUEST, ApiError.of("VALIDATION_FAILED", "request is invalid", e.problems()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiResponse<Void>> beanValidation(MethodArgumentNotValidException e) {
        List<String> problems = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).sorted().toList();
        return error(HttpStatus.BAD_REQUEST, ApiError.of("VALIDATION_FAILED", "request is invalid", problems));
    }

    @ExceptionHandler(CursorCodec.InvalidCursorException.class)
    ResponseEntity<ApiResponse<Void>> cursor(CursorCodec.InvalidCursorException e) {
        return error(HttpStatus.BAD_REQUEST, ApiError.of("INVALID_CURSOR", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiResponse<Void>> typeMismatch(MethodArgumentTypeMismatchException e) {
        return error(HttpStatus.BAD_REQUEST, ApiError.of("VALIDATION_FAILED", "request is invalid",
                List.of(e.getName() + " has an invalid value")));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiResponse<Void>> unreadable(HttpMessageNotReadableException e) {
        return error(HttpStatus.BAD_REQUEST, ApiError.of("MALFORMED_BODY", "request body is missing or not valid JSON"));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<ApiResponse<Void>> missingHeader(MissingRequestHeaderException e) {
        return error(HttpStatus.BAD_REQUEST, ApiError.of("MISSING_HEADER", "required header " + e.getHeaderName() + " is missing"));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiResponse<Void>> notFound(NotFoundException e) {
        return error(HttpStatus.NOT_FOUND, ApiError.of("NOT_FOUND", e.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ApiResponse<Void>> conflict(ConflictException e) {
        return error(HttpStatus.CONFLICT, ApiError.of(e.code(), e.getMessage()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ApiResponse<Void>> noResource(NoResourceFoundException e) {
        return error(HttpStatus.NOT_FOUND, ApiError.of("NOT_FOUND", "no such endpoint"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiResponse<Void>> method(HttpRequestMethodNotSupportedException e) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, ApiError.of("METHOD_NOT_ALLOWED", "method not allowed"));
    }

    @ExceptionHandler(QueryTimeoutException.class)
    ResponseEntity<ApiResponse<Void>> timeout(QueryTimeoutException e) {
        log.warn("search timed out: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, ApiError.of("QUERY_TIMEOUT",
                "query took too long; try fewer or more specific terms"));
    }

    @ExceptionHandler(IndexUnavailableException.class)
    ResponseEntity<ApiResponse<Void>> indexUnavailable(IndexUnavailableException e) {
        return error(HttpStatus.SERVICE_UNAVAILABLE, ApiError.of("INDEX_UNAVAILABLE", e.getMessage()));
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, TransientDataAccessException.class})
    ResponseEntity<ApiResponse<Void>> databaseUnavailable(RuntimeException e) {
        log.warn("database unavailable: {}", e.getMessage());
        return error(HttpStatus.SERVICE_UNAVAILABLE, ApiError.of("DATABASE_UNAVAILABLE",
                "the review database is temporarily unavailable; retry shortly"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiResponse<Void>> unexpected(Exception e) {
        log.error("unhandled error", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, ApiError.of("INTERNAL_ERROR", "internal server error"));
    }

    static ResponseEntity<ApiResponse<Void>> error(HttpStatus status, ApiError error) {
        return ResponseEntity.status(status).body(ApiResponse.failure(error));
    }
}
