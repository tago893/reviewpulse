package com.reviewpulse.search.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Opaque keyset cursors: base64url("1|fingerprint|scoreBits|reviewedAt|helpful|reviewId").
 *
 * <p>The fingerprint binds a cursor to the query, filters and sort it came from, so reusing a cursor
 * with different parameters is rejected (400) instead of silently returning a wrong page. The score
 * is stored as raw IEEE-754 bits so the comparison on the next page is exact.
 */
public final class CursorCodec {

    public static final int MAX_CURSOR_LENGTH = 256;
    private static final String VERSION = "1";

    private CursorCodec() {
    }

    public static String encode(SearchQuery query, SortKey key) {
        String raw = String.join("|", VERSION, fingerprint(query),
                Long.toHexString(Double.doubleToRawLongBits(key.score())),
                Long.toString(key.reviewedAt()), Integer.toString(key.helpfulVotes()), Long.toString(key.reviewId()));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.US_ASCII));
    }

    public static SortKey decode(String cursor, SearchQuery query) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_CURSOR_LENGTH) {
            throw new InvalidCursorException("cursor is malformed");
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
        } catch (IllegalArgumentException e) {
            throw new InvalidCursorException("cursor is malformed");
        }
        String[] parts = raw.split("\\|", -1);
        if (parts.length != 6 || !VERSION.equals(parts[0])) {
            throw new InvalidCursorException("cursor is malformed");
        }
        if (!fingerprint(query).equals(parts[1])) {
            throw new InvalidCursorException("cursor does not belong to this query, filters and sort order");
        }
        try {
            double score = Double.longBitsToDouble(Long.parseUnsignedLong(parts[2], 16));
            return new SortKey(score, Long.parseLong(parts[3]), Integer.parseInt(parts[4]), Long.parseLong(parts[5]));
        } catch (NumberFormatException e) {
            throw new InvalidCursorException("cursor is malformed");
        }
    }

    static String fingerprint(SearchQuery query) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(query.fingerprint().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Bad or foreign cursor supplied by a client. */
    public static class InvalidCursorException extends IllegalArgumentException {
        public InvalidCursorException(String message) {
            super(message);
        }
    }
}
