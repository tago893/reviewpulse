package com.reviewpulse.search.query;

import java.util.Arrays;
import java.util.Comparator;
import java.util.stream.IntStream;

import com.reviewpulse.search.index.InvertedIndex;
import com.reviewpulse.search.postings.PostingList;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.ranking.Bm25Scorer;

/**
 * Executes a {@link SearchQuery} against an {@link InvertedIndex} (document-at-a-time).
 *
 * <p>Match strategies, chosen per query:
 * <ul>
 *   <li><b>Product filter present</b>: the product's posting list (usually short) drives iteration;
 *       each term list is probed with galloping search. AND/OR semantics are applied per doc.</li>
 *   <li><b>AND</b>: leapfrog intersection starting from the shortest list, galloping in the others.
 *       O(k * m * log(n/m)) for shortest list length m.</li>
 *   <li><b>OR</b>: k-way merge with a binary min-heap of list cursors. O(N log k) for N total postings.</li>
 * </ul>
 * Dead documents and doc-table filters (rating, verified) are checked per candidate.
 *
 * <p>Not thread-safe per instance; create one per query. The caller must hold the index read lock.
 */
public final class QueryExecutor {

    private static final int DEADLINE_CHECK_INTERVAL = 4096;

    private final InvertedIndex index;
    private final SearchQuery query;
    private final PostingList[] lists;
    private final int[] positions;
    private final Bm25Scorer scorer;
    private final TopKCollector collector;
    private final long deadlineNanos;
    private final long budgetMillis;
    private int ticks;

    private QueryExecutor(InvertedIndex index, SearchQuery query, Bm25Parameters params, long budgetMillis) {
        this.index = index;
        this.query = query;
        int k = query.terms().size();
        this.lists = new PostingList[k];
        int[] dfs = new int[k];
        for (int i = 0; i < k; i++) {
            String term = query.terms().get(i);
            lists[i] = index.postings(term).orElse(null);
            dfs[i] = index.documentFrequency(term);
        }
        this.positions = new int[k];
        this.scorer = new Bm25Scorer(params, index.liveDocCount(), dfs,
                index.averageTitleLength(), index.averageBodyLength());
        this.collector = new TopKCollector(query.sort(), query.pageSize(), query.after());
        this.budgetMillis = budgetMillis;
        this.deadlineNanos = System.nanoTime() + budgetMillis * 1_000_000L;
    }

    /**
     * @param budgetMillis CPU-time budget; exceeding it throws {@link QueryTimeoutException}
     */
    public static SearchPage execute(InvertedIndex index, SearchQuery query, Bm25Parameters params, long budgetMillis) {
        if (query.terms().isEmpty()) {
            return SearchPage.EMPTY;
        }
        return new QueryExecutor(index, query, params, budgetMillis).run();
    }

    private SearchPage run() {
        boolean anyList = Arrays.stream(lists).anyMatch(l -> l != null && l.size() > 0);
        boolean allLists = Arrays.stream(lists).allMatch(l -> l != null && l.size() > 0);
        if ((query.operator() == Operator.AND && !allLists) || !anyList) {
            return SearchPage.EMPTY;
        }
        String asin = query.filters().parentAsin();
        if (asin != null) {
            PostingList product = index.productPostings(asin).orElse(null);
            if (product == null) {
                return SearchPage.EMPTY;
            }
            driveByProduct(product);
        } else if (query.operator() == Operator.AND) {
            intersect();
        } else {
            union();
        }
        return collector.page();
    }

    // ------------------------------------------------------------------ strategies

    private void driveByProduct(PostingList product) {
        boolean and = query.operator() == Operator.AND;
        for (int p = 0; p < product.size(); p++) {
            tick();
            int doc = product.docAt(p);
            if (!index.isLive(doc) || !query.filters().accepts(index, doc)) {
                continue;
            }
            int matched = 0;
            for (int i = 0; i < lists.length; i++) {
                if (lists[i] == null) {
                    continue;
                }
                positions[i] = lists[i].advance(positions[i], doc);
                if (positions[i] < lists[i].size() && lists[i].docAt(positions[i]) == doc) {
                    matched++;
                }
            }
            if (and ? matched == lists.length : matched > 0) {
                emit(doc);
            }
        }
    }

    private void intersect() {
        int[] order = IntStream.range(0, lists.length).boxed()
                .sorted(Comparator.comparingInt(i -> lists[i].size()))
                .mapToInt(Integer::intValue).toArray();
        PostingList lead = lists[order[0]];
        int leadPos = 0;
        outer:
        while (leadPos < lead.size()) {
            tick();
            int candidate = lead.docAt(leadPos);
            for (int j = 1; j < order.length; j++) {
                int li = order[j];
                positions[li] = lists[li].advance(positions[li], candidate);
                if (positions[li] >= lists[li].size()) {
                    break outer;
                }
                int doc = lists[li].docAt(positions[li]);
                if (doc > candidate) {
                    leadPos = lead.advance(leadPos, doc);
                    continue outer;
                }
            }
            positions[order[0]] = leadPos;
            acceptCandidate(candidate);
            leadPos++;
        }
    }

    private void union() {
        int[] heap = new int[lists.length];
        int size = 0;
        for (int i = 0; i < lists.length; i++) {
            if (lists[i] != null && lists[i].size() > 0) {
                heap[size++] = i;
            }
        }
        for (int i = size / 2 - 1; i >= 0; i--) {
            siftDown(heap, size, i);
        }
        while (size > 0) {
            tick();
            int doc = current(heap[0]);
            acceptCandidate(doc);
            while (size > 0 && current(heap[0]) == doc) {
                int li = heap[0];
                positions[li]++;
                if (positions[li] >= lists[li].size()) {
                    heap[0] = heap[--size];
                }
                siftDown(heap, size, 0);
            }
        }
    }

    private int current(int listIndex) {
        return lists[listIndex].docAt(positions[listIndex]);
    }

    private void siftDown(int[] heap, int size, int i) {
        while (true) {
            int left = 2 * i + 1;
            if (left >= size) {
                return;
            }
            int right = left + 1;
            int smallest = right < size && current(heap[right]) < current(heap[left]) ? right : left;
            if (current(heap[i]) <= current(heap[smallest])) {
                return;
            }
            int tmp = heap[i];
            heap[i] = heap[smallest];
            heap[smallest] = tmp;
            i = smallest;
        }
    }

    // ------------------------------------------------------------------ scoring and collection

    private void acceptCandidate(int doc) {
        if (index.isLive(doc) && query.filters().accepts(index, doc)) {
            emit(doc);
        }
    }

    private void emit(int doc) {
        long reviewId = index.reviewId(doc);
        long reviewedAt = index.reviewedAt(doc);
        int helpful = index.helpfulVotes(doc);
        double score = query.sort().usesScore() ? score(doc) : 0.0;
        if (collector.wouldAccept(reviewId, score, reviewedAt, helpful)) {
            if (!query.sort().usesScore()) {
                score = score(doc);
            }
            collector.add(new Hit(doc, reviewId, score, reviewedAt, helpful, index.version(doc)));
        }
    }

    /** Sums term contributions in fixed query-term order, so floating-point results are reproducible. */
    private double score(int doc) {
        double total = 0.0;
        int titleLength = index.titleLength(doc);
        int bodyLength = index.bodyLength(doc);
        for (int i = 0; i < lists.length; i++) {
            PostingList list = lists[i];
            if (list == null) {
                continue;
            }
            int pos = list.advance(positions[i], doc);
            if (pos < list.size() && list.docAt(pos) == doc) {
                total += scorer.termScore(i, list.titleTfAt(pos), list.bodyTfAt(pos), titleLength, bodyLength);
            }
        }
        return total;
    }

    private void tick() {
        if (++ticks % DEADLINE_CHECK_INTERVAL == 0 && System.nanoTime() > deadlineNanos) {
            throw new QueryTimeoutException(budgetMillis);
        }
    }
}
