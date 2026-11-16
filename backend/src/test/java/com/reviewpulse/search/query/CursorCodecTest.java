package com.reviewpulse.search.query;

import static com.reviewpulse.search.SearchFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.reviewpulse.search.ranking.SortOrder;

class CursorCodecTest {

    private final SearchQuery q = query("dry skin", Operator.AND, SortOrder.RELEVANCE, 10);

    @Test
    void roundTripsExactly() {
        SortKey key = new SortKey(3.141592653589793, 1_588_687_728_923L, 17, 4_611_686_018_427_387_905L);
        String cursor = CursorCodec.encode(q, key);
        assertThat(cursor).doesNotContain("+", "/", "=");
        assertThat(CursorCodec.decode(cursor, q)).isEqualTo(key);
    }

    @Test
    void pageSizeChangeKeepsCursorValid() {
        SortKey key = new SortKey(1.5, 1, 2, 3);
        String cursor = CursorCodec.encode(q, key);
        assertThat(CursorCodec.decode(cursor, query("dry skin", Operator.AND, SortOrder.RELEVANCE, 50))).isEqualTo(key);
    }

    @Test
    void rejectsCursorFromDifferentQueryOrSort() {
        String cursor = CursorCodec.encode(q, new SortKey(1.0, 1, 1, 1));
        assertThatThrownBy(() -> CursorCodec.decode(cursor, query("dry hair", Operator.AND, SortOrder.RELEVANCE, 10)))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
        assertThatThrownBy(() -> CursorCodec.decode(cursor, query("dry skin", Operator.AND, SortOrder.RECENCY, 10)))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
        assertThatThrownBy(() -> CursorCodec.decode(cursor, query("dry skin", Operator.OR, SortOrder.RELEVANCE, 10)))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "!!!", "bm90LWEtY3Vyc29y", "MXxhYmN8eHx5fHp8dw"})
    void rejectsMalformedCursors(String cursor) {
        assertThatThrownBy(() -> CursorCodec.decode(cursor, q)).isInstanceOf(CursorCodec.InvalidCursorException.class);
    }

    @Test
    void rejectsOversizedCursor() {
        assertThatThrownBy(() -> CursorCodec.decode("A".repeat(CursorCodec.MAX_CURSOR_LENGTH + 1), q))
                .isInstanceOf(CursorCodec.InvalidCursorException.class);
    }
}
