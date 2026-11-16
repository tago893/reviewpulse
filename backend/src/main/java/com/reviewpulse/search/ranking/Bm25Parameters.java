package com.reviewpulse.search.ranking;

/**
 * BM25F parameters.
 *
 * <p>Defaults: k1 = 1.2 and b = 0.75 are the standard Robertson/Lucene values. Title matches get
 * weight 2.0 versus 1.0 for the body because a review title is a short, deliberate summary.
 * These were chosen a priori, not tuned on a relevance-judged set (none exists for this dataset).
 *
 * @param k1          term-frequency saturation (higher = repeated terms keep adding score longer)
 * @param titleB      title length normalization strength in [0, 1]
 * @param bodyB       body length normalization strength in [0, 1]
 * @param titleWeight field boost for title occurrences
 * @param bodyWeight  field boost for body occurrences
 */
public record Bm25Parameters(double k1, double titleB, double bodyB, double titleWeight, double bodyWeight) {

    public static final Bm25Parameters DEFAULT = new Bm25Parameters(1.2, 0.75, 0.75, 2.0, 1.0);

    public Bm25Parameters {
        if (k1 <= 0 || titleB < 0 || titleB > 1 || bodyB < 0 || bodyB > 1 || titleWeight < 0 || bodyWeight < 0) {
            throw new IllegalArgumentException("invalid BM25 parameters");
        }
    }
}
