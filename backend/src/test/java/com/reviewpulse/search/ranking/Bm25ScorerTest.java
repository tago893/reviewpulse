package com.reviewpulse.search.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class Bm25ScorerTest {

    @Test
    void idfMatchesHandComputedValues() {
        // N=2, df=1: ln(1 + 1.5/1.5) = ln 2
        assertThat(Bm25Scorer.idf(2, 1)).isCloseTo(Math.log(2), within(1e-12));
        // A term in every document still has a small positive idf (Lucene-style +1 inside the log).
        assertThat(Bm25Scorer.idf(100, 100)).isPositive().isLessThan(0.01);
        assertThat(Bm25Scorer.idf(100, 1)).isGreaterThan(Bm25Scorer.idf(100, 50));
    }

    @Test
    void termScoreMatchesHandComputedExample() {
        // Corpus: d1 body "apple" (len 1), d2 body "banana banana" (len 2). No titles.
        // avgBody = 1.5, avgTitle = 0 -> treated as 1.0.
        // d1, term "apple": norm = 0.25 + 0.75 * 1/1.5 = 0.75; tf~ = 1/0.75 = 1.3333
        // s = ln2 * 1.3333 * 2.2 / (1.2 + 1.3333) = 0.6931472 * 1.1578947 = 0.8025915
        Bm25Scorer scorer = new Bm25Scorer(Bm25Parameters.DEFAULT, 2, new int[] {1}, 0.0, 1.5);
        assertThat(scorer.termScore(0, 0, 1, 0, 1)).isCloseTo(0.8025915, within(1e-6));
    }

    @Test
    void titleOccurrencesOutweighBodyOccurrencesAtEqualLength() {
        Bm25Scorer scorer = new Bm25Scorer(Bm25Parameters.DEFAULT, 10, new int[] {2}, 3.0, 3.0);
        double inTitle = scorer.termScore(0, 1, 0, 3, 3);
        double inBody = scorer.termScore(0, 0, 1, 3, 3);
        assertThat(inTitle).isGreaterThan(inBody);
    }

    @Test
    void termFrequencySaturatesAndLongerDocumentsScoreLower() {
        Bm25Scorer scorer = new Bm25Scorer(Bm25Parameters.DEFAULT, 10, new int[] {2}, 5.0, 20.0);
        double tf1 = scorer.termScore(0, 0, 1, 5, 20);
        double tf2 = scorer.termScore(0, 0, 2, 5, 20);
        double tf20 = scorer.termScore(0, 0, 20, 5, 20);
        assertThat(tf2 - tf1).isGreaterThan(tf20 - scorer.termScore(0, 0, 19, 5, 20));
        assertThat(tf20).isLessThan(scorer.idf(0) * (Bm25Parameters.DEFAULT.k1() + 1));
        assertThat(scorer.termScore(0, 0, 1, 5, 80)).isLessThan(tf1);
        assertThat(scorer.termScore(0, 0, 0, 5, 20)).isZero();
    }

    @Test
    void rejectsInvalidParameters() {
        assertThatThrownBy(() -> new Bm25Parameters(0, 0.75, 0.75, 1, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bm25Parameters(1.2, 1.5, 0.75, 1, 1)).isInstanceOf(IllegalArgumentException.class);
    }
}
