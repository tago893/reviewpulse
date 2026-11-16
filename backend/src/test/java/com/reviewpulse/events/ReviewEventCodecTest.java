package com.reviewpulse.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.reviewpulse.search.index.IndexMutation;

import tools.jackson.databind.json.JsonMapper;

class ReviewEventCodecTest {

    private final ReviewEventCodec codec = new ReviewEventCodec(JsonMapper.builder().build());
    private static final Instant T = Instant.parse("2026-11-15T10:00:00Z");

    private static ReviewEvent upsert(long id, long version) {
        return new ReviewEvent(UUID.randomUUID(), EventType.REVIEW_UPDATED, id, version, T,
                new ReviewEvent.State("B00YQ6X8EO", "Title", "Body \"quoted\" ünïcode", 4, 2, true, T));
    }

    @Test
    void roundTripsUpsertAndDelete() {
        ReviewEvent event = upsert(4_611_686_018_427_387_905L, 3);
        String json = codec.encode(event);
        assertThat(json).contains("\"reviewId\":\"4611686018427387905\"");
        assertThat(codec.decode(json.getBytes(StandardCharsets.UTF_8))).isEqualTo(event);

        ReviewEvent delete = new ReviewEvent(UUID.randomUUID(), EventType.REVIEW_DELETED, 7, 9, T, null);
        ReviewEvent decoded = codec.decode(codec.encode(delete).getBytes(StandardCharsets.UTF_8));
        assertThat(decoded).isEqualTo(delete);
        assertThat(decoded.toMutation()).isEqualTo(new IndexMutation.Delete(7, 9));
    }

    @Test
    void upsertBecomesIndexMutationWithFullState() {
        IndexMutation m = upsert(5, 2).toMutation();
        assertThat(m).isInstanceOf(IndexMutation.Upsert.class);
        assertThat(((IndexMutation.Upsert) m).review().helpfulVotes()).isEqualTo(2);
        assertThat(m.version()).isEqualTo(2);
    }

    @Test
    void ignoresUnknownFieldsForForwardCompatibility() {
        String json = codec.encode(upsert(5, 2)).replace("{\"schemaVersion\"", "{\"newField\":42,\"schemaVersion\"");
        assertThat(codec.decode(json.getBytes(StandardCharsets.UTF_8)).reviewId()).isEqualTo(5);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "not json",
            "[1,2,3]",
            "{\"schemaVersion\":2,\"eventId\":\"a\"}",
            "{\"schemaVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"eventType\":\"BOGUS\",\"reviewId\":\"1\",\"version\":1,\"occurredAt\":\"2026-11-15T10:00:00Z\"}",
            "{\"schemaVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"eventType\":\"REVIEW_CREATED\",\"reviewId\":\"-4\",\"version\":1,\"occurredAt\":\"2026-11-15T10:00:00Z\"}",
            "{\"schemaVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"eventType\":\"REVIEW_CREATED\",\"reviewId\":\"1\",\"version\":0,\"occurredAt\":\"2026-11-15T10:00:00Z\"}",
            "{\"schemaVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"eventType\":\"REVIEW_CREATED\",\"reviewId\":\"1\",\"version\":1,\"occurredAt\":\"2026-11-15T10:00:00Z\",\"review\":null}",
            "{\"schemaVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"eventType\":\"REVIEW_CREATED\",\"reviewId\":\"1\",\"version\":1,\"occurredAt\":\"2026-11-15T10:00:00Z\",\"review\":{\"parentAsin\":\"B00YQ6X8EO\",\"rating\":9,\"helpfulVotes\":0,\"reviewedAt\":\"2026-11-15T10:00:00Z\"}}",
            "{\"schemaVersion\":1,\"eventId\":\"00000000-0000-0000-0000-000000000001\",\"eventType\":\"REVIEW_DELETED\",\"reviewId\":\"1\",\"version\":1,\"occurredAt\":\"yesterday\"}"})
    void malformedOrInvalidEventsArePoison(String payload) {
        assertThatThrownBy(() -> codec.decode(payload.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(PoisonEventException.class);
    }

    @Test
    void nullPayloadIsPoison() {
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(PoisonEventException.class);
    }
}
