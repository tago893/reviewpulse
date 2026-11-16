package com.reviewpulse.search.highlight;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

import com.reviewpulse.search.highlight.Highlighter.HighlightedText;
import com.reviewpulse.search.highlight.Highlighter.Range;
import com.reviewpulse.search.tokenizer.Tokenizer;

class HighlighterTest {

    private final Highlighter highlighter = new Highlighter(Tokenizer.DEFAULT);

    private static String marked(HighlightedText h) {
        StringBuilder sb = new StringBuilder(h.text());
        for (int i = h.highlights().size() - 1; i >= 0; i--) {
            Range r = h.highlights().get(i);
            sb.insert(r.end(), "]").insert(r.start(), "[");
        }
        return sb.toString();
    }

    @Test
    void highlightsAllMatchesUsingNormalizedTerms() {
        HighlightedText h = highlighter.highlightAll("Great Café for DRY skin", Set.of("cafe", "dry"));
        assertThat(marked(h)).isEqualTo("Great [Café] for [DRY] skin");
    }

    @Test
    void snippetCentersOnDensestMatchWindowWithEllipses() {
        String body = "word ".repeat(60) + "this cream is perfect for dry skin and very dry hands " + "filler ".repeat(60);
        HighlightedText h = highlighter.snippet(body.trim(), Set.of("dry", "skin"));
        assertThat(h.text()).startsWith("…").endsWith("…");
        assertThat(marked(h)).contains("[dry] [skin]").contains("very [dry] hands");
        for (Range r : h.highlights()) {
            assertThat(h.text().substring(r.start(), r.end())).isIn("dry", "skin");
        }
    }

    @Test
    void shortBodyWithoutMatchesIsReturnedWhole() {
        HighlightedText h = highlighter.snippet("Nice scent.", Set.of("dry"));
        assertThat(h.text()).isEqualTo("Nice scent.");
        assertThat(h.highlights()).isEmpty();
    }

    @Test
    void longBodyWithoutMatchesIsTruncatedAtWordBoundary() {
        HighlightedText h = highlighter.snippet("lorem ipsum ".repeat(50), Set.of("dry"));
        assertThat(h.text()).endsWith("…").hasSizeLessThanOrEqualTo(Highlighter.FALLBACK_CHARS + 1);
    }

    @Test
    void emptyBodyGivesEmptySnippet() {
        assertThat(highlighter.snippet("", Set.of("dry")).text()).isEmpty();
    }
}
