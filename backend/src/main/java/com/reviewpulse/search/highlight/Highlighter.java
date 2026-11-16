package com.reviewpulse.search.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.reviewpulse.search.tokenizer.Token;
import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Produces snippets and highlight ranges. Ranges are character offsets into the returned text, so the
 * client renders them with text nodes and never injects HTML (no XSS surface).
 * Matching uses the same tokenizer as the index, so "Café" in the text is highlighted for "cafe".
 */
public final class Highlighter {

    /** Half-open character range [start, end). */
    public record Range(int start, int end) {
    }

    /** Text plus the ranges to emphasize. */
    public record HighlightedText(String text, List<Range> highlights) {
        public HighlightedText {
            highlights = List.copyOf(highlights);
        }
    }

    static final int WINDOW_TOKENS = 30;
    static final int FALLBACK_CHARS = 220;
    static final String ELLIPSIS = "…";

    private final Tokenizer tokenizer;

    public Highlighter(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
    }

    /** Highlights every matching token in a short text such as a title. */
    public HighlightedText highlightAll(String text, Set<String> terms) {
        List<Range> ranges = new ArrayList<>();
        for (Token t : tokenizer.tokenize(text)) {
            if (terms.contains(t.term())) {
                ranges.add(new Range(t.start(), t.end()));
            }
        }
        return new HighlightedText(text, ranges);
    }

    /**
     * Picks the window of {@value #WINDOW_TOKENS} tokens containing the most distinct query terms
     * (ties: most total matches, then earliest) and returns it with ellipses where text was cut.
     */
    public HighlightedText snippet(String body, Set<String> terms) {
        List<Token> tokens = tokenizer.tokenize(body);
        int[] window = bestWindow(tokens, terms);
        if (window == null) {
            return prefix(body);
        }
        int charStart = tokens.get(window[0]).start();
        int charEnd = tokens.get(window[1]).end();
        boolean cutStart = charStart > 0;
        boolean cutEnd = charEnd < body.length();
        String core = body.substring(charStart, charEnd);
        int shift = (cutStart ? ELLIPSIS.length() : 0) - charStart;
        List<Range> ranges = new ArrayList<>();
        for (int i = window[0]; i <= window[1]; i++) {
            Token t = tokens.get(i);
            if (terms.contains(t.term())) {
                ranges.add(new Range(t.start() + shift, t.end() + shift));
            }
        }
        String text = (cutStart ? ELLIPSIS : "") + core + (cutEnd ? ELLIPSIS : "");
        return new HighlightedText(text, ranges);
    }

    private static int[] bestWindow(List<Token> tokens, Set<String> terms) {
        int bestStart = -1;
        int bestDistinct = 0;
        int bestTotal = 0;
        for (int start = 0; start < tokens.size(); start++) {
            if (!terms.contains(tokens.get(start).term())) {
                continue;
            }
            int end = Math.min(tokens.size(), start + WINDOW_TOKENS);
            Set<String> distinct = new java.util.HashSet<>();
            int total = 0;
            for (int i = start; i < end; i++) {
                if (terms.contains(tokens.get(i).term())) {
                    distinct.add(tokens.get(i).term());
                    total++;
                }
            }
            if (distinct.size() > bestDistinct || (distinct.size() == bestDistinct && total > bestTotal)) {
                bestStart = start;
                bestDistinct = distinct.size();
                bestTotal = total;
            }
        }
        if (bestStart < 0) {
            return null;
        }
        // Start a few tokens before the first match for context, keeping the window size.
        int contextStart = Math.max(0, bestStart - 4);
        int end = Math.min(tokens.size(), contextStart + WINDOW_TOKENS) - 1;
        return new int[] {contextStart, end};
    }

    private static HighlightedText prefix(String body) {
        if (body.length() <= FALLBACK_CHARS) {
            return new HighlightedText(body, List.of());
        }
        int cut = body.lastIndexOf(' ', FALLBACK_CHARS);
        cut = cut > FALLBACK_CHARS / 2 ? cut : FALLBACK_CHARS;
        return new HighlightedText(body.substring(0, cut) + ELLIPSIS, List.of());
    }
}
