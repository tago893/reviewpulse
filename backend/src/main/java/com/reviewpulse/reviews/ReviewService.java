package com.reviewpulse.reviews;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.reviewpulse.api.ConflictException;
import com.reviewpulse.api.NotFoundException;
import com.reviewpulse.events.EventType;
import com.reviewpulse.events.ReviewEvent;
import com.reviewpulse.events.ReviewEventCodec;
import com.reviewpulse.outbox.OutboxRepository;

/**
 * Review writes. Each method is ONE database transaction that changes the review row and inserts the
 * matching outbox event, so "review changed" and "event will be published" commit or roll back together.
 * Indexing happens later and asynchronously; callers get the committed version and can poll
 * GET /api/reviews/{id} for {@code searchable}.
 */
@Service
public class ReviewService {

    private final ReviewRepository reads;
    private final ReviewWriteRepository writes;
    private final OutboxRepository outbox;
    private final ReviewEventCodec codec;

    public ReviewService(ReviewRepository reads, ReviewWriteRepository writes, OutboxRepository outbox,
                         ReviewEventCodec codec) {
        this.reads = reads;
        this.writes = writes;
        this.outbox = outbox;
        this.codec = codec;
    }

    /** Request to create a review through the API. */
    public record CreateCommand(String parentAsin, String title, String body, Integer rating, Boolean verifiedPurchase) {
    }

    /** Full replacement of the mutable fields, guarded by the version the client last saw. */
    public record UpdateCommand(Long expectedVersion, String title, String body, Integer rating) {
    }

    /** A dataset review replayed as a live arrival (deterministic id; duplicates are skipped). */
    public record ReplayCommand(long id, String sourceKey, String parentAsin, String asin, String title, String body,
                                int rating, int helpfulVotes, boolean verifiedPurchase, Instant reviewedAt) {
    }

    @Transactional
    public Review create(CreateCommand cmd) {
        List<String> problems = new ArrayList<>();
        String asin = ReviewInput.parentAsin(cmd.parentAsin(), problems);
        ReviewInput.Content content = ReviewInput.content(cmd.title(), cmd.body(), cmd.rating(), problems);
        ReviewInput.throwIfAny(problems);
        Review review = writes.insertApiReview(asin, content.title(), content.body(), content.rating(),
                Boolean.TRUE.equals(cmd.verifiedPurchase()), Instant.now());
        return emit(EventType.REVIEW_CREATED, review);
    }

    @Transactional
    public Review update(long id, UpdateCommand cmd) {
        List<String> problems = new ArrayList<>();
        long expected = ReviewInput.expectedVersion(cmd.expectedVersion(), problems);
        ReviewInput.Content content = ReviewInput.content(cmd.title(), cmd.body(), cmd.rating(), problems);
        ReviewInput.throwIfAny(problems);
        Review review = writes.updateContent(id, expected, content.title(), content.body(), content.rating())
                .orElseThrow(() -> conflictOrMissing(id, expected));
        return emit(EventType.REVIEW_UPDATED, review);
    }

    @Transactional
    public Review delete(long id, Long expectedVersion) {
        List<String> problems = new ArrayList<>();
        long expected = ReviewInput.expectedVersion(expectedVersion, problems);
        ReviewInput.throwIfAny(problems);
        Review review = writes.softDelete(id, expected).orElseThrow(() -> conflictOrMissing(id, expected));
        return emit(EventType.REVIEW_DELETED, review);
    }

    @Transactional
    public Review markHelpful(long id) {
        Review review = writes.incrementHelpful(id).orElseThrow(() -> new NotFoundException("review not found"));
        return emit(EventType.HELPFULNESS_UPDATED, review);
    }

    @Transactional
    public Optional<Review> importReplayed(ReplayCommand cmd) {
        List<String> problems = new ArrayList<>();
        String asin = ReviewInput.parentAsin(cmd.parentAsin(), problems);
        ReviewInput.Content content = ReviewInput.content(cmd.title(), cmd.body(), cmd.rating(), problems);
        ReviewInput.throwIfAny(problems);
        return writes.insertReplayReview(cmd.id(), cmd.sourceKey(), asin, cmd.asin(), content.title(), content.body(),
                        content.rating(), cmd.helpfulVotes(), cmd.verifiedPurchase(), cmd.reviewedAt())
                .map(review -> emit(EventType.REVIEW_CREATED, review));
    }

    private Review emit(EventType type, Review review) {
        ReviewEvent event = ReviewEvent.of(type, review, review.updatedAt());
        outbox.insert(event, codec.encode(event));
        return review;
    }

    private RuntimeException conflictOrMissing(long id, long expected) {
        return reads.findById(id)
                .filter(r -> !r.deleted())
                .<RuntimeException>map(r -> new ConflictException("VERSION_CONFLICT",
                        "review was modified: expected version " + expected + ", current version " + r.version()))
                .orElseGet(() -> new NotFoundException("review not found"));
    }
}
