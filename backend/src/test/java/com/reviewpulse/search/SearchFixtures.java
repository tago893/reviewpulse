package com.reviewpulse.search;

import java.util.List;

import com.reviewpulse.search.index.IndexedReview;
import com.reviewpulse.search.index.InvertedIndex;
import com.reviewpulse.search.query.Operator;
import com.reviewpulse.search.query.SearchFilters;
import com.reviewpulse.search.query.SearchQuery;
import com.reviewpulse.search.ranking.SortOrder;
import com.reviewpulse.search.tokenizer.Tokenizer;

/** Hand-written, deterministic review fixtures for search tests (synthetic text, not dataset records). */
public final class SearchFixtures {

    public static final String P1 = "B000000001";
    public static final String P2 = "B000000002";

    private SearchFixtures() {
    }

    public static IndexedReview review(long id, long version, String product, String title, String body,
                                       int rating, int helpful, boolean verified, long reviewedAt) {
        return new IndexedReview(id, version, product, title, body, rating, helpful, verified, reviewedAt);
    }

    /** Ten labeled reviews used by the hand-checked query tests. */
    public static List<IndexedReview> labeledCorpus() {
        return List.of(
                review(1, 1, P1, "Great for dry skin", "This cream fixed my dry skin in a week.", 5, 10, true, 1_000),
                review(2, 1, P1, "Too greasy", "Leaves an oily film, not good for oily skin.", 2, 3, true, 2_000),
                review(3, 1, P2, "Smells lovely", "Nice scent, lasts all day.", 4, 0, false, 3_000),
                review(4, 1, P2, "Dry dry dry", "My hair felt dry after using it.", 1, 1, true, 4_000),
                review(5, 1, P1, "Not greasy at all", "Absorbs fast, great on sensitive skin.", 5, 25, true, 5_000),
                review(6, 1, P2, "Okay", "Does the job. Nothing special about the scent.", 3, 0, false, 6_000),
                review(7, 1, P1, "Skin feels smooth", "Smooth skin after two uses, no irritation.", 5, 7, false, 7_000),
                review(8, 1, P2, "Broke me out", "Caused breakouts on my sensitive skin.", 1, 12, true, 8_000),
                review(9, 1, P1, "Dry skin savior", "Winter dry skin is gone. Smooth and soft.", 5, 2, true, 9_000),
                review(10, 1, P2, "Shampoo review", "Hair is soft and shiny.", 4, 0, true, 10_000));
    }

    public static InvertedIndex indexOf(List<IndexedReview> reviews) {
        InvertedIndex index = new InvertedIndex(Tokenizer.DEFAULT);
        reviews.forEach(index::upsert);
        return index;
    }

    public static SearchQuery query(String q, Operator op, SortOrder sort, int pageSize) {
        return new SearchQuery(Tokenizer.DEFAULT.queryTerms(q), op, SearchFilters.NONE, sort, pageSize, null);
    }

    public static SearchQuery query(String q, Operator op, SearchFilters filters, SortOrder sort, int pageSize) {
        return new SearchQuery(Tokenizer.DEFAULT.queryTerms(q), op, filters, sort, pageSize, null);
    }
}
