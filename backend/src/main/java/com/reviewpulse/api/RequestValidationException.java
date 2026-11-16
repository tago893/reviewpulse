package com.reviewpulse.api;

import java.util.List;

/** One or more client input problems; mapped to HTTP 400 with every problem listed. */
public class RequestValidationException extends RuntimeException {

    private final List<String> problems;

    public RequestValidationException(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
