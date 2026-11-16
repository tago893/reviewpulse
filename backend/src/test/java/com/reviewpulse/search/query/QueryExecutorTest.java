package com.reviewpulse.search.query;

import static com.reviewpulse.search.SearchFixtures.P1;
import static com.reviewpulse.search.SearchFixtures.P2;
import static com.reviewpulse.search.SearchFixtures.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.reviewpulse.search.ReferenceSearch;
import com.reviewpulse.search.SearchFixtures;
import com.reviewpulse.search.index.IndexedReview;
import com.reviewpulse.search.index.InvertedIndex;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.ranking.SortOrder;
import com.reviewpulse.search.tokenizer.Tokenizer;

class QueryExecutorTest {

    private static final Bm25Parameters P = Bm25Parameters.DEFAULT;

    private static List<Long> ids(InvertedIndex index, SearchQuery q) {
        return QueryExecutor.execute(index, q, P, 1_000).hits().stream().map(Hit::reviewId).toList();
    }

    /** Expected results below were labeled by reading SearchFixtures.labeledCorpus() by hand. */
    @Nested
    class HandLabeledCorpus {

        private final InvertedIndex index = SearchFixtures.indexOf(SearchFixtures.labeledCorpus());

        @Test
        void andRequiresEveryTerm() {
            // Only #1 and #9 contain both "dry" and "skin". #1 ranks first: identical term
            // frequencies, but its body is one token shorter (5 vs 6), so length normalization favors it.
            assertThat(ids(index, query("dry skin", Operator.AND, SortOrder.RELEVANCE, 10))).containsExactly(1L, 9L);
        }

        @Test
        void orMatchesAnyTermAndRanksMultiTermMatchesFirst() {
            List<Long> result = ids(index, query("dry skin", Operator.OR, SortOrder.RELEVANCE, 10));
            assertThat(result).containsExactlyInAnyOrder(1L, 2L, 4L, 5L, 7L, 8L, 9L);
            assertThat(result.subList(0, 2)).containsExactlyInAnyOrder(1L, 9L);
        }

        @Test
        void negationsAreSearchable() {
            assertThat(ids(index, query("not greasy", Operator.AND, SortOrder.RELEVANCE, 10)))
                    .containsExactlyInAnyOrder(2L, 5L);
        }

        @Test
        void filtersCombineWithRanking() {
            var fiveStars = new SearchFilters(5, 5, null, null);
            assertThat(ids(index, query("skin", Operator.OR, fiveStars, SortOrder.RELEVANCE, 10)))
                    .containsExactlyInAnyOrder(1L, 5L, 7L, 9L);
            var unverified = new SearchFilters(null, null, null, false);
            assertThat(ids(index, query("scent", Operator.AND, unverified, SortOrder.RELEVANCE, 10)))
                    .containsExactlyInAnyOrder(3L, 6L);
            var productTwo = new SearchFilters(null, null, P2, null);
            assertThat(ids(index, query("skin", Operator.OR, productTwo, SortOrder.RELEVANCE, 10))).containsExactly(8L);
            var lowRatedP1 = new SearchFilters(1, 2, P1, true);
            assertThat(ids(index, query("skin oily", Operator.OR, lowRatedP1, SortOrder.RELEVANCE, 10))).containsExactly(2L);
            var unknownProduct = new SearchFilters(null, null, "B999999999", null);
            assertThat(ids(index, query("skin", Operator.OR, unknownProduct, SortOrder.RELEVANCE, 10))).isEmpty();
        }

        @Test
        void recencyAndHelpfulnessSorts() {
            assertThat(ids(index, query("skin", Operator.OR, SortOrder.RECENCY, 10))).containsExactly(9L, 8L, 7L, 5L, 2L, 1L);
            assertThat(ids(index, query("skin", Operator.OR, SortOrder.HELPFULNESS, 10))).containsExactly(5L, 8L, 1L, 7L, 2L, 9L);
        }

        @Test
        void emptyStopWordOnlyAndOutOfVocabularyQueries() {
            assertThat(ids(index, query("", Operator.OR, SortOrder.RELEVANCE, 10))).isEmpty();
            assertThat(ids(index, query("the and of", Operator.OR, SortOrder.RELEVANCE, 10))).isEmpty();
            assertThat(ids(index, query("unicorn", Operator.OR, SortOrder.RELEVANCE, 10))).isEmpty();
            assertThat(ids(index, query("dry unicorn", Operator.AND, SortOrder.RELEVANCE, 10))).isEmpty();
            assertThat(ids(index, query("dry unicorn", Operator.OR, SortOrder.RELEVANCE, 10)))
                    .containsExactlyInAnyOrder(1L, 4L, 9L);
        }

        @Test
        void totalHitsAndHasMoreReflectAllMatches() {
            SearchPage page = QueryExecutor.execute(index, query("skin", Operator.OR, SortOrder.RECENCY, 2), P, 1_000);
            assertThat(page.hits()).hasSize(2);
            assertThat(page.totalHits()).isEqualTo(6);
            assertThat(page.hasMore()).isTrue();
        }

        @Test
        void everyReturnedHitCarriesItsRelevanceScoreEvenWhenSortedByRecency() {
            SearchPage page = QueryExecutor.execute(index, query("dry skin", Operator.AND, SortOrder.RECENCY, 10), P, 1_000);
            assertThat(page.hits()).allSatisfy(h -> assertThat(h.score()).isPositive());
        }
    }

    @Nested
    class Pagination {

        @ParameterizedTest
        @ValueSource(strings = {"RELEVANCE", "RECENCY", "HELPFULNESS"})
        void keysetPagesHaveNoDuplicatesOrGaps(String sortName) {
            InvertedIndex index = randomIndex(new Random(7), 300);
            SortOrder sort = SortOrder.valueOf(sortName);
            SearchQuery base = query("alpha beta gamma", Operator.OR, sort, 7);
            List<Long> all = ids(index, query("alpha beta gamma", Operator.OR, sort, 50));
            List<Long> paged = collectAllPages(index, base);
            SearchPage first = QueryExecutor.execute(index, base, P, 1_000);
            assertThat(paged).hasSize((int) first.totalHits());
            assertThat(new HashSet<>(paged)).hasSize(paged.size());
            assertThat(paged.subList(0, Math.min(50, paged.size()))).isEqualTo(all);
        }
    }

    @Nested
    class AgainstReferenceImplementation {

        @ParameterizedTest
        @ValueSource(longs = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12})
        void randomCorpusAndMutationsMatchBruteForce(long seed) {
            Random random = new Random(seed);
            InvertedIndex index = new InvertedIndex(Tokenizer.DEFAULT);
            Map<Long, IndexedReview> live = new HashMap<>();
            applyRandomMutations(random, index, live, 400);

            ReferenceSearch reference = new ReferenceSearch(live.values(), P);
            InvertedIndex compacted = index.compact();
            for (int i = 0; i < 40; i++) {
                SearchQuery q = randomQuery(random);
                List<ReferenceSearch.RefHit> expected = reference.search(q);
                assertMatches(index, q, expected);
                assertMatches(compacted, q, expected);
            }
        }

        private void assertMatches(InvertedIndex index, SearchQuery q, List<ReferenceSearch.RefHit> expected) {
            List<Hit> actual = collectAllHits(index, q);
            assertThat(actual).extracting(Hit::reviewId)
                    .as("query %s", q.fingerprint())
                    .containsExactlyElementsOf(expected.stream().map(ReferenceSearch.RefHit::reviewId).toList());
            for (int i = 0; i < actual.size(); i++) {
                assertThat(actual.get(i).score()).isCloseTo(expected.get(i).score(), within(1e-12));
            }
        }
    }

    @Test
    void exceedingTheTimeBudgetFailsInsteadOfReturningPartialResults() {
        InvertedIndex index = randomIndex(new Random(3), 30_000);
        SearchQuery q = query("alpha beta gamma delta epsilon", Operator.OR, SortOrder.RELEVANCE, 10);
        assertThatThrownBy(() -> QueryExecutor.execute(index, q, P, 0)).isInstanceOf(QueryTimeoutException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static final String[] VOCAB = {
            "alpha", "beta", "gamma", "delta", "epsilon", "zeta", "eta", "theta", "iota", "kappa",
            "lambda", "mu", "nu", "xi", "omicron", "pi", "rho", "sigma", "tau", "upsilon"};
    private static final String[] PRODUCTS = {"B00000000A", "B00000000B", "B00000000C", "B00000000D"};

    private static String words(Random random, int min, int max) {
        int n = min + random.nextInt(max - min + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(i == 0 ? "" : random.nextBoolean() ? " " : ", ").append(VOCAB[random.nextInt(VOCAB.length)]);
        }
        return sb.toString();
    }

    private static IndexedReview randomReview(Random random, long id, long version) {
        return new IndexedReview(id, version, PRODUCTS[random.nextInt(PRODUCTS.length)], words(random, 0, 4),
                words(random, 1, 15), 1 + random.nextInt(5), random.nextInt(4), random.nextBoolean(),
                random.nextInt(50));
    }

    private static InvertedIndex randomIndex(Random random, int n) {
        InvertedIndex index = new InvertedIndex(Tokenizer.DEFAULT);
        for (int i = 1; i <= n; i++) {
            index.upsert(randomReview(random, i, 1));
        }
        return index;
    }

    /** Applies inserts, content/metadata updates, deletes, duplicates and stale events. */
    static void applyRandomMutations(Random random, InvertedIndex index, Map<Long, IndexedReview> live, int ops) {
        Map<Long, Long> versions = new HashMap<>();
        List<IndexedReview> history = new ArrayList<>();
        long nextId = 1;
        for (int op = 0; op < ops; op++) {
            double r = random.nextDouble();
            List<Long> liveIds = new ArrayList<>(live.keySet());
            liveIds.sort(Long::compare);
            if (r < 0.5 || liveIds.isEmpty()) {
                IndexedReview created = randomReview(random, nextId++, 1);
                index.upsert(created);
                live.put(created.reviewId(), created);
                versions.put(created.reviewId(), 1L);
                history.add(created);
            } else if (r < 0.65) {
                long id = liveIds.get(random.nextInt(liveIds.size()));
                IndexedReview updated = randomReview(random, id, versions.get(id) + 1);
                index.upsert(updated);
                live.put(id, updated);
                versions.put(id, updated.version());
                history.add(updated);
            } else if (r < 0.8) {
                long id = liveIds.get(random.nextInt(liveIds.size()));
                IndexedReview old = live.get(id);
                IndexedReview meta = new IndexedReview(id, old.version() + 1, old.parentAsin(), old.title(), old.body(),
                        1 + random.nextInt(5), random.nextInt(4), random.nextBoolean(), random.nextInt(50));
                index.upsert(meta);
                live.put(id, meta);
                versions.put(id, meta.version());
                history.add(meta);
            } else if (r < 0.9) {
                long id = liveIds.get(random.nextInt(liveIds.size()));
                long v = versions.get(id) + 1;
                index.delete(id, v);
                live.remove(id);
                versions.put(id, v);
            } else if (!history.isEmpty()) {
                // duplicate or stale replay of an earlier version: must be ignored
                IndexedReview replay = history.get(random.nextInt(history.size()));
                index.upsert(replay);
            }
        }
    }

    private static SearchQuery randomQuery(Random random) {
        int n = 1 + random.nextInt(3);
        StringBuilder q = new StringBuilder();
        for (int i = 0; i < n; i++) {
            q.append(VOCAB[random.nextInt(VOCAB.length)]).append(' ');
        }
        if (random.nextInt(10) == 0) {
            q.append("unknownterm");
        }
        Integer min = random.nextInt(3) == 0 ? 1 + random.nextInt(5) : null;
        Integer max = random.nextInt(3) == 0 ? (min == null ? 1 + random.nextInt(5) : min + random.nextInt(6 - min)) : null;
        String asin = random.nextInt(4) == 0 ? PRODUCTS[random.nextInt(PRODUCTS.length)] : null;
        Boolean verified = random.nextInt(4) == 0 ? random.nextBoolean() : null;
        Operator op = random.nextBoolean() ? Operator.AND : Operator.OR;
        SortOrder sort = SortOrder.values()[random.nextInt(3)];
        return new SearchQuery(Tokenizer.DEFAULT.queryTerms(q.toString()), op,
                new SearchFilters(min, max, asin, verified), sort, 1 + random.nextInt(7), null);
    }

    private static List<Hit> collectAllHits(InvertedIndex index, SearchQuery base) {
        List<Hit> out = new ArrayList<>();
        SearchQuery q = base;
        for (int guard = 0; guard < 10_000; guard++) {
            SearchPage page = QueryExecutor.execute(index, q, P, 1_000);
            out.addAll(page.hits());
            if (!page.hasMore()) {
                return out;
            }
            q = q.withAfter(page.hits().getLast().sortKey());
        }
        throw new AssertionError("pagination did not terminate");
    }

    private static List<Long> collectAllPages(InvertedIndex index, SearchQuery base) {
        return collectAllHits(index, base).stream().map(Hit::reviewId).toList();
    }
}
