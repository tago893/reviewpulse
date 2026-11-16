package com.reviewpulse.search.index;

import static com.reviewpulse.search.SearchFixtures.P1;
import static com.reviewpulse.search.SearchFixtures.P2;
import static com.reviewpulse.search.SearchFixtures.review;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.reviewpulse.search.query.Operator;
import com.reviewpulse.search.query.QueryExecutor;
import com.reviewpulse.search.query.SearchPage;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.ranking.SortOrder;
import com.reviewpulse.search.SearchFixtures;
import com.reviewpulse.search.tokenizer.Tokenizer;

class InvertedIndexTest {

    private final InvertedIndex index = new InvertedIndex(Tokenizer.DEFAULT);

    private List<Long> ids(String q) {
        SearchPage page = QueryExecutor.execute(index, SearchFixtures.query(q, Operator.OR, SortOrder.RELEVANCE, 50),
                Bm25Parameters.DEFAULT, 1_000);
        return page.hits().stream().map(h -> h.reviewId()).sorted().toList();
    }

    @Test
    void buildsPostingsAndExactStatistics() {
        index.upsert(review(1, 1, P1, "Dry skin", "dry dry cream", 5, 0, true, 1));
        index.upsert(review(2, 1, P1, "", "oily skin", 3, 0, true, 2));
        assertThat(index.documentFrequency("dry")).isEqualTo(1);
        assertThat(index.documentFrequency("skin")).isEqualTo(2);
        var dry = index.postings("dry").orElseThrow();
        assertThat(dry.size()).isEqualTo(1);
        assertThat(dry.titleTfAt(0)).isEqualTo(1);
        assertThat(dry.bodyTfAt(0)).isEqualTo(2);
        assertThat(index.liveDocCount()).isEqualTo(2);
        assertThat(index.averageTitleLength()).isEqualTo(1.0);
        assertThat(index.averageBodyLength()).isEqualTo(2.5);
        assertThat(index.postings("unknown")).isEmpty();
    }

    @Test
    void duplicateAndOlderVersionsAreIgnored() {
        assertThat(index.upsert(review(1, 2, P1, "new title", "new body", 5, 0, true, 1))).isEqualTo(ApplyOutcome.INSERTED);
        assertThat(index.upsert(review(1, 2, P1, "new title", "new body", 5, 0, true, 1))).isEqualTo(ApplyOutcome.STALE_IGNORED);
        assertThat(index.upsert(review(1, 1, P1, "old title", "old body", 5, 0, true, 1))).isEqualTo(ApplyOutcome.STALE_IGNORED);
        assertThat(ids("old")).isEmpty();
        assertThat(ids("new")).containsExactly(1L);
        assertThat(index.appliedVersion(1)).hasValue(2);
    }

    @Test
    void contentUpdateReplacesOldTermsWithNewTerms() {
        index.upsert(review(1, 1, P1, "Greasy mess", "awful greasy", 1, 0, true, 1));
        assertThat(index.upsert(review(1, 2, P1, "Lovely", "smooth finish", 5, 0, true, 1)))
                .isEqualTo(ApplyOutcome.CONTENT_UPDATED);
        assertThat(ids("greasy")).isEmpty();
        assertThat(ids("smooth")).containsExactly(1L);
        assertThat(index.documentFrequency("greasy")).isZero();
        assertThat(index.liveDocCount()).isEqualTo(1);
        assertThat(index.docSlots()).as("old docId is dead, not reused").isEqualTo(2);
    }

    @Test
    void metadataOnlyUpdateDoesNotTouchPostings() {
        index.upsert(review(1, 1, P1, "Nice", "smooth skin", 4, 0, false, 1));
        long postingsBefore = index.stats().postings();
        assertThat(index.upsert(review(1, 2, P1, "Nice", "smooth skin", 2, 42, true, 99)))
                .isEqualTo(ApplyOutcome.METADATA_UPDATED);
        assertThat(index.stats().postings()).isEqualTo(postingsBefore);
        assertThat(index.docSlots()).isEqualTo(1);
        int doc = index.liveDocId(1).orElseThrow();
        assertThat(index.helpfulVotes(doc)).isEqualTo(42);
        assertThat(index.rating(doc)).isEqualTo(2);
        assertThat(index.verifiedPurchase(doc)).isTrue();
        assertThat(index.version(doc)).isEqualTo(2);
    }

    @Test
    void productChangeIsTreatedAsContentChange() {
        index.upsert(review(1, 1, P1, "Nice", "smooth", 4, 0, false, 1));
        assertThat(index.upsert(review(1, 2, P2, "Nice", "smooth", 4, 0, false, 1))).isEqualTo(ApplyOutcome.CONTENT_UPDATED);
        int doc = index.liveDocId(1).orElseThrow();
        assertThat(index.parentAsin(doc)).isEqualTo(P2);
    }

    @Test
    void deleteRemovesFromSearchAndTombstoneBlocksOlderEvents() {
        index.upsert(review(1, 1, P1, "Smooth", "smooth skin", 5, 0, true, 1));
        assertThat(index.delete(1, 2)).isEqualTo(ApplyOutcome.DELETED);
        assertThat(ids("smooth")).isEmpty();
        assertThat(index.documentFrequency("smooth")).isZero();
        assertThat(index.liveDocCount()).isZero();
        assertThat(index.upsert(review(1, 1, P1, "Smooth", "smooth skin", 5, 0, true, 1))).isEqualTo(ApplyOutcome.STALE_IGNORED);
        assertThat(index.delete(1, 2)).isEqualTo(ApplyOutcome.STALE_IGNORED);
        assertThat(ids("smooth")).isEmpty();
    }

    @Test
    void deleteBeforeCreateRecordsTombstone() {
        assertThat(index.delete(7, 3)).isEqualTo(ApplyOutcome.TOMBSTONE_RECORDED);
        assertThat(index.upsert(review(7, 2, P1, "late create", "x", 5, 0, true, 1))).isEqualTo(ApplyOutcome.STALE_IGNORED);
        assertThat(ids("late")).isEmpty();
    }

    @Test
    void compactionDropsDeadDocsAndPreservesResultsAndStatistics() {
        for (int i = 1; i <= 50; i++) {
            index.upsert(review(i, 1, i % 2 == 0 ? P1 : P2, "title " + i, "smooth skin number" + i, 1 + i % 5, i, true, i));
        }
        for (int i = 1; i <= 30; i++) {
            index.upsert(review(i, 2, P1, "changed " + i, "rough skin", 3, 0, false, i));
        }
        for (int i = 31; i <= 40; i++) {
            index.delete(i, 2);
        }
        List<Long> smoothBefore = ids("smooth");
        List<Long> roughBefore = ids("rough skin");
        IndexStats before = index.stats();

        InvertedIndex compacted = index.compact();
        IndexStats after = compacted.stats();

        assertThat(after.liveDocs()).isEqualTo(before.liveDocs()).isEqualTo(40);
        assertThat(after.docSlots()).isEqualTo(40);
        assertThat(after.postings()).isLessThan(before.postings());
        assertThat(after.trackedReviews()).isEqualTo(50);
        assertThat(compacted.documentFrequency("skin")).isEqualTo(index.documentFrequency("skin"));
        assertThat(compacted.averageBodyLength()).isEqualTo(index.averageBodyLength());
        assertThat(compacted.postings("number35")).as("term only in deleted docs is dropped").isEmpty();
        assertThat(compacted.appliedVersion(35)).as("tombstone survives compaction").hasValue(2);

        InvertedIndex original = index;
        assertThat(searchIds(compacted, "smooth")).isEqualTo(smoothBefore).isEqualTo(searchIds(original, "smooth"));
        assertThat(searchIds(compacted, "rough skin")).isEqualTo(roughBefore);
    }

    private static List<Long> searchIds(InvertedIndex idx, String q) {
        return QueryExecutor.execute(idx, SearchFixtures.query(q, Operator.OR, SortOrder.RELEVANCE, 50),
                        Bm25Parameters.DEFAULT, 1_000)
                .hits().stream().map(h -> h.reviewId()).sorted().toList();
    }
}
