package com.reviewpulse.api;

import java.util.List;

/** Parses review ids from paths. Ids are transported as strings because they exceed 2^53. */
public final class ReviewIds {

    private ReviewIds() {
    }

    public static long parse(String raw) {
        try {
            long id = Long.parseLong(raw);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException e) {
            // fall through to the validation error
        }
        throw new RequestValidationException(List.of("review id must be a positive 64-bit integer"));
    }
}
