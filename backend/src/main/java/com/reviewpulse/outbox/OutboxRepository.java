package com.reviewpulse.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.reviewpulse.events.ReviewEvent;

@Repository
public class OutboxRepository {

    /** A row claimed for publishing. */
    public record PendingEvent(long id, UUID eventId, long reviewId, long reviewVersion, String eventType,
                               String payload, Instant createdAt, int attempts) {
    }

    private final JdbcClient jdbc;

    public OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Must be called inside the transaction that changed the review. */
    public void insert(ReviewEvent event, String payload) {
        jdbc.sql("""
                INSERT INTO outbox_events (event_id, review_id, review_version, event_type, payload, created_at)
                VALUES (:eventId, :reviewId, :version, :type, CAST(:payload AS jsonb), :createdAt)
                """)
                .param("eventId", event.eventId())
                .param("reviewId", event.reviewId())
                .param("version", event.version())
                .param("type", event.type().name())
                .param("payload", payload)
                .param("createdAt", java.sql.Timestamp.from(event.occurredAt()))
                .update();
    }

    /**
     * Locks up to {@code limit} pending rows in id order. SKIP LOCKED lets a second publisher instance
     * (if one were ever started) take different rows instead of blocking or double-sending.
     */
    public List<PendingEvent> lockPending(int limit) {
        return jdbc.sql("""
                SELECT id, event_id, review_id, review_version, event_type, payload::text AS payload, created_at, attempts
                FROM outbox_events
                WHERE status = 'PENDING'
                ORDER BY id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """)
                .param("limit", limit)
                .query((rs, n) -> new PendingEvent(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getLong("review_id"), rs.getLong("review_version"), rs.getString("event_type"),
                        rs.getString("payload"), rs.getTimestamp("created_at").toInstant(), rs.getInt("attempts")))
                .list();
    }

    public void markPublished(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE outbox_events SET status = 'PUBLISHED', published_at = now() WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(Long[]::new))
                .update();
    }

    /** Records a failed attempt; {@code permanent} parks the row as FAILED (visible in metrics). */
    public void recordFailure(long id, String error, boolean permanent) {
        jdbc.sql("""
                UPDATE outbox_events
                SET attempts = attempts + 1, last_error = :error,
                    status = CASE WHEN :permanent THEN 'FAILED' ELSE status END
                WHERE id = :id
                """)
                .param("id", id)
                .param("error", error.length() > 500 ? error.substring(0, 500) : error)
                .param("permanent", permanent)
                .update();
    }

    public long countByStatus(String status) {
        return jdbc.sql("SELECT count(*) FROM outbox_events WHERE status = :status")
                .param("status", status).query(Long.class).single();
    }

    /** Age of the oldest pending event (database clock), or zero when nothing is pending. */
    public Duration oldestPendingAge() {
        Long millis = jdbc.sql("""
                SELECT COALESCE(CAST(EXTRACT(EPOCH FROM (now() - min(created_at))) * 1000 AS BIGINT), 0)
                FROM outbox_events WHERE status = 'PENDING'
                """).query(Long.class).single();
        return Duration.ofMillis(Math.max(0, millis));
    }

    public int deletePublishedBefore(Instant cutoff) {
        return jdbc.sql("DELETE FROM outbox_events WHERE status = 'PUBLISHED' AND published_at < :cutoff")
                .param("cutoff", java.sql.Timestamp.from(cutoff))
                .update();
    }
}
