package com.reviewpulse.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.reviewpulse.reviews.Review;

class ReviewEventTest {

    private static final Instant T = Instant.parse("2026-11-15T10:00:00Z");
    private static final Review REVIEW = new Review(9, "API", "B00YQ6X8EO", null, "t", "b", 4, 2, true, T, T, T, 3, false);

    @Test
    void deletesCarryNoStateAndEverythingElseMust() {
        ReviewEvent.State state = new ReviewEvent.State("B00YQ6X8EO", "t", "b", 4, 2, true, T);
        assertThatThrownBy(() -> new ReviewEvent(UUID.randomUUID(), EventType.REVIEW_DELETED, 9, 3, T, state))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewEvent(UUID.randomUUID(), EventType.REVIEW_UPDATED, 9, 3, T, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ofBuildsFullStateFromTheCommittedRow() {
        ReviewEvent helpful = ReviewEvent.of(EventType.HELPFULNESS_UPDATED, REVIEW, T);
        assertThat(helpful.version()).isEqualTo(3);
        assertThat(helpful.state().helpfulVotes()).isEqualTo(2);
        assertThat(ReviewEvent.of(EventType.REVIEW_DELETED, REVIEW, T).state()).isNull();
    }
}
