package com.reviewpulse.reviews;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Review mutations. Every statement returns the row as committed (RETURNING), which is exactly the
 * state the outbox event must carry. Conditional updates implement optimistic concurrency:
 * {@code WHERE version = :expected} affects zero rows if someone else changed the review first.
 */
@Repository
public class ReviewWriteRepository {

    private static final String RETURNING = " RETURNING " + ReviewRepository.COLUMNS;

    private final JdbcClient jdbc;

    public ReviewWriteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Review insertApiReview(String parentAsin, String title, String body, int rating, boolean verified,
                                  Instant reviewedAt) {
        return jdbc.sql("""
                INSERT INTO reviews (id, source, parent_asin, title, body, rating, verified_purchase, reviewed_at)
                VALUES (nextval('review_api_id_seq'), 'API', :asin, :title, :body, :rating, :verified, :reviewedAt)
                """ + RETURNING)
                .param("asin", parentAsin).param("title", title).param("body", body).param("rating", rating)
                .param("verified", verified).param("reviewedAt", Timestamp.from(reviewedAt))
                .query(ReviewRepository.MAPPER).single();
    }

    /** Inserts a replayed dataset review unless its source key already exists. */
    public Optional<Review> insertReplayReview(long id, String sourceKey, String parentAsin, String asin, String title,
                                               String body, int rating, int helpful, boolean verified, Instant reviewedAt) {
        return jdbc.sql("""
                INSERT INTO reviews (id, source, source_key, parent_asin, asin, title, body, rating, helpful_votes,
                                     verified_purchase, reviewed_at)
                VALUES (:id, 'REPLAY', :key, :parentAsin, :asin, :title, :body, :rating, :helpful, :verified, :reviewedAt)
                ON CONFLICT DO NOTHING
                """ + RETURNING)
                .param("id", id).param("key", sourceKey).param("parentAsin", parentAsin).param("asin", asin)
                .param("title", title).param("body", body).param("rating", rating).param("helpful", helpful)
                .param("verified", verified).param("reviewedAt", Timestamp.from(reviewedAt))
                .query(ReviewRepository.MAPPER).optional();
    }

    public Optional<Review> updateContent(long id, long expectedVersion, String title, String body, int rating) {
        return jdbc.sql("""
                UPDATE reviews SET title = :title, body = :body, rating = :rating,
                                   version = version + 1, updated_at = now()
                WHERE id = :id AND version = :expected AND NOT deleted
                """ + RETURNING)
                .param("id", id).param("expected", expectedVersion)
                .param("title", title).param("body", body).param("rating", rating)
                .query(ReviewRepository.MAPPER).optional();
    }

    public Optional<Review> softDelete(long id, long expectedVersion) {
        return jdbc.sql("""
                UPDATE reviews SET deleted = TRUE, deleted_at = now(), version = version + 1, updated_at = now()
                WHERE id = :id AND version = :expected AND NOT deleted
                """ + RETURNING)
                .param("id", id).param("expected", expectedVersion)
                .query(ReviewRepository.MAPPER).optional();
    }

    /** Commutative, so no expected version: the row lock serializes concurrent votes. */
    public Optional<Review> incrementHelpful(long id) {
        return jdbc.sql("""
                UPDATE reviews SET helpful_votes = helpful_votes + 1, version = version + 1, updated_at = now()
                WHERE id = :id AND NOT deleted
                """ + RETURNING)
                .param("id", id)
                .query(ReviewRepository.MAPPER).optional();
    }
}
