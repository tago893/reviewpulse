package com.reviewpulse.events;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.reviewpulse.search.index.IndexedReview;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON wire format for {@link ReviewEvent}, schema version 1:
 * <pre>
 * {"schemaVersion":1,"eventId":"uuid","eventType":"REVIEW_UPDATED","reviewId":"123","version":3,
 *  "occurredAt":"2026-11-15T10:00:00Z",
 *  "review":{"parentAsin":"B00...","title":"..","body":"..","rating":5,"helpfulVotes":0,
 *            "verifiedPurchase":true,"reviewedAt":"..."}}
 * </pre>
 * reviewId is a string because dataset ids exceed 2^53. Unknown fields are ignored (forward compatible);
 * missing or invalid required fields make the event poison.
 */
public final class ReviewEventCodec {

    private final JsonMapper mapper;

    public ReviewEventCodec(JsonMapper mapper) {
        this.mapper = mapper;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireReview(String parentAsin, String title, String body, Integer rating, Integer helpfulVotes,
                      Boolean verifiedPurchase, String reviewedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record WireEvent(Integer schemaVersion, String eventId, String eventType, String reviewId, Long version,
                     String occurredAt, WireReview review) {
    }

    public String encode(ReviewEvent event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("schemaVersion", ReviewEvent.SCHEMA_VERSION);
        json.put("eventId", event.eventId().toString());
        json.put("eventType", event.type().name());
        json.put("reviewId", Long.toString(event.reviewId()));
        json.put("version", event.version());
        json.put("occurredAt", event.occurredAt().toString());
        if (event.state() != null) {
            ReviewEvent.State s = event.state();
            Map<String, Object> review = new LinkedHashMap<>();
            review.put("parentAsin", s.parentAsin());
            review.put("title", s.title());
            review.put("body", s.body());
            review.put("rating", s.rating());
            review.put("helpfulVotes", s.helpfulVotes());
            review.put("verifiedPurchase", s.verifiedPurchase());
            review.put("reviewedAt", s.reviewedAt().toString());
            json.put("review", review);
        } else {
            json.put("review", null);
        }
        return mapper.writeValueAsString(json);
    }

    /** @throws PoisonEventException if the bytes are not a valid schema-1 event */
    public ReviewEvent decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PoisonEventException("empty event payload");
        }
        WireEvent wire;
        try {
            wire = mapper.readValue(bytes, WireEvent.class);
        } catch (JacksonException e) {
            throw new PoisonEventException("event payload is not valid JSON for schema 1", e);
        }
        try {
            return toEvent(wire);
        } catch (IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            throw new PoisonEventException("event failed validation: " + e.getMessage(), e);
        }
    }

    private static ReviewEvent toEvent(WireEvent w) {
        if (w.schemaVersion() == null || w.schemaVersion() != ReviewEvent.SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion " + w.schemaVersion());
        }
        EventType type = EventType.valueOf(require(w.eventType(), "eventType"));
        long reviewId = Long.parseLong(require(w.reviewId(), "reviewId"));
        if (reviewId <= 0) {
            throw new IllegalArgumentException("reviewId must be positive");
        }
        if (w.version() == null || w.version() < 1) {
            throw new IllegalArgumentException("version must be >= 1");
        }
        ReviewEvent.State state = null;
        if (type != EventType.REVIEW_DELETED) {
            WireReview r = w.review();
            if (r == null) {
                throw new IllegalArgumentException("review state is required for " + type);
            }
            state = new ReviewEvent.State(require(r.parentAsin(), "parentAsin"), nullToEmpty(r.title()),
                    nullToEmpty(r.body()), requireInt(r.rating(), "rating"), requireInt(r.helpfulVotes(), "helpfulVotes"),
                    Boolean.TRUE.equals(r.verifiedPurchase()), Instant.parse(require(r.reviewedAt(), "reviewedAt")));
            // Reuse the index's own invariants (rating range, non-negative votes, etc.).
            new IndexedReview(reviewId, w.version(), state.parentAsin(), state.title(), state.body(), state.rating(),
                    state.helpfulVotes(), state.verifiedPurchase(), state.reviewedAt().toEpochMilli());
        }
        return new ReviewEvent(UUID.fromString(require(w.eventId(), "eventId")), type, reviewId, w.version(),
                Instant.parse(require(w.occurredAt(), "occurredAt")), state);
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static int requireInt(Integer value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
