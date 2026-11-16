package com.reviewpulse.reviews;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.reviewpulse.api.RequestValidationException;

/**
 * Validation and normalization of client-supplied review content, applied identically to API
 * writes and the replay simulator. Limits match the database CHECK constraints.
 */
public final class ReviewInput {

    public static final int MAX_TITLE_CHARS = 500;
    public static final int MAX_BODY_CHARS = 32_000;
    private static final Pattern ASIN = Pattern.compile("^[A-Z0-9]{10}$");
    // C0 controls except \t and \n, plus DEL. PostgreSQL rejects NUL in text outright.
    private static final Pattern CONTROL = Pattern.compile("[\\x00-\\x08\\x0B-\\x1F\\x7F]");

    /** Normalized content ready to store. */
    public record Content(String title, String body, int rating) {
    }

    private ReviewInput() {
    }

    public static String clean(String raw) {
        if (raw == null) {
            return "";
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = CONTROL.matcher(text).replaceAll("");
        return Normalizer.normalize(text, Normalizer.Form.NFC).strip();
    }

    public static Content content(String title, String body, Integer rating, List<String> problems) {
        String t = clean(title).replace('\n', ' ');
        String b = clean(body);
        if (t.length() > MAX_TITLE_CHARS) {
            problems.add("title must be at most " + MAX_TITLE_CHARS + " characters");
        }
        if (b.length() > MAX_BODY_CHARS) {
            problems.add("body must be at most " + MAX_BODY_CHARS + " characters");
        }
        if (t.isEmpty() && b.isEmpty()) {
            problems.add("title or body must contain text");
        }
        if (rating == null || rating < 1 || rating > 5) {
            problems.add("rating must be an integer from 1 to 5");
        }
        return new Content(t, b, rating == null ? 0 : rating);
    }

    public static String parentAsin(String raw, List<String> problems) {
        String asin = raw == null ? "" : raw.strip();
        if (!ASIN.matcher(asin).matches()) {
            problems.add("parentAsin must be 10 uppercase letters or digits");
        }
        return asin;
    }

    public static long expectedVersion(Long raw, List<String> problems) {
        if (raw == null || raw < 1) {
            problems.add("expectedVersion is required and must be >= 1");
            return 0;
        }
        return raw;
    }

    public static void throwIfAny(List<String> problems) {
        if (!problems.isEmpty()) {
            throw new RequestValidationException(new ArrayList<>(problems));
        }
    }
}
