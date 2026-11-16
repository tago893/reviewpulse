package com.reviewpulse.search;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.reviewpulse.search.index.IndexedReview;
import com.reviewpulse.search.query.Operator;
import com.reviewpulse.search.query.SearchQuery;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.ranking.SortOrder;
import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Independent brute-force implementation of the search semantics: tokenizes every document on
 * every query, recomputes all statistics from scratch and sorts the full match list. It shares
 * only the tokenizer with the real engine, so it checks postings, intersection/union, statistics,
 * BM25F arithmetic, filters, sorting and pagination.
 */
public final class ReferenceSearch {

    public record RefHit(long reviewId, double score) {
    }

    private record Doc(IndexedReview review, Map<String, int[]> tf, int titleLength, int bodyLength) {
    }

    private final List<Doc> docs = new ArrayList<>();
    private final Bm25Parameters params;

    public ReferenceSearch(Collection<IndexedReview> liveReviews, Bm25Parameters params) {
        this.params = params;
        for (IndexedReview r : liveReviews) {
            Map<String, int[]> tf = new HashMap<>();
            int[] lengths = new int[2];
            Tokenizer.DEFAULT.forEachTerm(r.title(), t -> {
                tf.computeIfAbsent(t, k -> new int[2])[0]++;
                lengths[0]++;
            });
            Tokenizer.DEFAULT.forEachTerm(r.body(), t -> {
                tf.computeIfAbsent(t, k -> new int[2])[1]++;
                lengths[1]++;
            });
            docs.add(new Doc(r, tf, lengths[0], lengths[1]));
        }
    }

    public List<RefHit> search(SearchQuery q) {
        int n = docs.size();
        double avgT = docs.stream().mapToInt(Doc::titleLength).average().orElse(0);
        double avgB = docs.stream().mapToInt(Doc::bodyLength).average().orElse(0);
        avgT = avgT > 0 ? avgT : 1.0;
        avgB = avgB > 0 ? avgB : 1.0;
        List<String> terms = q.terms();
        double[] idf = new double[terms.size()];
        for (int i = 0; i < terms.size(); i++) {
            String term = terms.get(i);
            long df = docs.stream().filter(d -> d.tf().containsKey(term)).count();
            idf[i] = Math.log(1.0 + (n - df + 0.5) / (df + 0.5));
        }
        List<Doc> matches = new ArrayList<>();
        Map<Long, Double> scores = new HashMap<>();
        for (Doc d : docs) {
            long matched = terms.stream().filter(d.tf()::containsKey).count();
            boolean ok = terms.isEmpty() ? false
                    : q.operator() == Operator.AND ? matched == terms.size() : matched > 0;
            if (!ok || !passesFilters(d.review(), q)) {
                continue;
            }
            double s = 0;
            for (int i = 0; i < terms.size(); i++) {
                int[] f = d.tf().get(terms.get(i));
                if (f == null) {
                    continue;
                }
                double nt = 1 - params.titleB() + params.titleB() * d.titleLength() / avgT;
                double nb = 1 - params.bodyB() + params.bodyB() * d.bodyLength() / avgB;
                double tf = params.titleWeight() * f[0] / nt + params.bodyWeight() * f[1] / nb;
                s += idf[i] * tf * (params.k1() + 1) / (params.k1() + tf);
            }
            matches.add(d);
            scores.put(d.review().reviewId(), s);
        }
        SortOrder sort = q.sort();
        Comparator<Doc> cmp = (a, b) -> sort.compare(
                scores.get(a.review().reviewId()), a.review().reviewedAtEpochMillis(), a.review().helpfulVotes(),
                a.review().reviewId(), scores.get(b.review().reviewId()), b.review().reviewedAtEpochMillis(),
                b.review().helpfulVotes(), b.review().reviewId());
        matches.sort(cmp);
        return matches.stream().map(d -> new RefHit(d.review().reviewId(), scores.get(d.review().reviewId()))).toList();
    }

    private static boolean passesFilters(IndexedReview r, SearchQuery q) {
        var f = q.filters();
        return (f.ratingMin() == null || r.rating() >= f.ratingMin())
                && (f.ratingMax() == null || r.rating() <= f.ratingMax())
                && (f.parentAsin() == null || r.parentAsin().equals(f.parentAsin()))
                && (f.verifiedPurchase() == null || r.verifiedPurchase() == f.verifiedPurchase());
    }
}
