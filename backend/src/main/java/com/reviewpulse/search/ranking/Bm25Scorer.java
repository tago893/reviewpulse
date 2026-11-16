package com.reviewpulse.search.ranking;

/**
 * BM25F scoring (Robertson &amp; Zaragoza, "The Probabilistic Relevance Framework: BM25 and Beyond").
 *
 * <pre>
 *   idf(t)   = ln(1 + (N - df + 0.5) / (df + 0.5))                       (always positive)
 *   tf~(t,d) = wT * tfT / (1 - bT + bT * lenT/avgT)  +  wB * tfB / (1 - bB + bB * lenB/avgB)
 *   s(t,d)   = idf(t) * tf~ * (k1 + 1) / (k1 + tf~)
 *   score(d) = sum over matched query terms t of s(t,d)
 * </pre>
 * Fields are combined before saturation (BM25F), so a term repeated across title and body does not
 * get two independent saturation curves. Statistics come from live documents only.
 *
 * <p>Immutable; one instance per query.
 */
public final class Bm25Scorer {

    private final Bm25Parameters params;
    private final double[] idf;
    private final double avgTitle;
    private final double avgBody;

    /**
     * @param liveDocs           N, number of searchable documents
     * @param documentFrequency  df per query term, aligned with query term order
     * @param averageTitleLength mean title length (tokens) over live docs
     * @param averageBodyLength  mean body length (tokens) over live docs
     */
    public Bm25Scorer(Bm25Parameters params, int liveDocs, int[] documentFrequency,
                      double averageTitleLength, double averageBodyLength) {
        this.params = params;
        this.idf = new double[documentFrequency.length];
        for (int i = 0; i < documentFrequency.length; i++) {
            this.idf[i] = idf(liveDocs, documentFrequency[i]);
        }
        this.avgTitle = averageTitleLength > 0 ? averageTitleLength : 1.0;
        this.avgBody = averageBodyLength > 0 ? averageBodyLength : 1.0;
    }

    public static double idf(int liveDocs, int df) {
        return Math.log(1.0 + (liveDocs - df + 0.5) / (df + 0.5));
    }

    public double idf(int termIndex) {
        return idf[termIndex];
    }

    /** Contribution of query term {@code termIndex} to a document's score. */
    public double termScore(int termIndex, int titleTf, int bodyTf, int titleLength, int bodyLength) {
        double normTitle = 1.0 - params.titleB() + params.titleB() * titleLength / avgTitle;
        double normBody = 1.0 - params.bodyB() + params.bodyB() * bodyLength / avgBody;
        double tf = params.titleWeight() * titleTf / normTitle + params.bodyWeight() * bodyTf / normBody;
        if (tf <= 0) {
            return 0.0;
        }
        return idf[termIndex] * tf * (params.k1() + 1.0) / (params.k1() + tf);
    }
}
