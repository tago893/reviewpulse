package com.reviewpulse.search.tokenizer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * Deterministic, Unicode-aware tokenizer shared by indexing and querying.
 *
 * <p>Rules, applied identically to documents and queries:
 * <ol>
 *   <li>A token is a maximal run of letters, digits and combining marks (by Unicode category).
 *       Everything else (whitespace, punctuation, symbols, emoji) separates tokens.</li>
 *   <li>An apostrophe (U+0027 or U+2019) between two token characters is dropped and joins the parts:
 *       "don't" becomes "dont", "it's" becomes "its".</li>
 *   <li>Non-ASCII tokens are NFKD-normalized, combining marks are removed (diacritic folding:
 *       "café" matches "cafe"), and compatibility forms are folded ("ﬁ" becomes "fi", full-width
 *       "ＡＢＣ" becomes "abc").</li>
 *   <li>Lowercasing uses {@link Character#toLowerCase(int)}, which is locale-independent.</li>
 *   <li>Tokens longer than {@link #MAX_TOKEN_LENGTH} code points are dropped (URLs, base64, garbage).</li>
 *   <li>Stop words ({@link StopWords#ENGLISH}) are dropped after normalization.</li>
 * </ol>
 * Scripts written without spaces (Chinese, Japanese, Thai) are not segmented: a run becomes one token.
 * The dataset is English, so this is a documented limitation rather than a bug.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Tokenizer {

    public static final int MAX_TOKEN_LENGTH = 40;
    public static final Tokenizer DEFAULT = new Tokenizer(StopWords.ENGLISH);

    /** Identifies the analysis rules. Persisted indexes built with different rules are rejected. */
    public static final String FINGERPRINT = "rp-tokenizer-v1;maxlen=" + MAX_TOKEN_LENGTH
            + ";stop=" + new TreeSet<>(StopWords.ENGLISH).hashCode();

    private final Set<String> stopWords;

    public Tokenizer(Set<String> stopWords) {
        this.stopWords = Set.copyOf(stopWords);
    }

    /** Callback for the allocation-light indexing path. */
    @FunctionalInterface
    public interface TermSink {
        void accept(String term);
    }

    /** Emits normalized, non-stop-word terms in order. Used when building the index. */
    public void forEachTerm(String text, TermSink sink) {
        scan(text, (term, start, end) -> sink.accept(term));
    }

    /** Tokens with original offsets, for highlighting and snippets. */
    public List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        scan(text, (term, start, end) -> tokens.add(new Token(term, start, end, tokens.size())));
        return tokens;
    }

    /** Distinct query terms in first-occurrence order. */
    public List<String> queryTerms(String query) {
        Set<String> terms = new LinkedHashSet<>();
        forEachTerm(query, terms::add);
        return List.copyOf(terms);
    }

    @FunctionalInterface
    private interface SpanSink {
        void accept(String term, int start, int end);
    }

    private void scan(String text, SpanSink sink) {
        if (text == null || text.isEmpty()) {
            return;
        }
        int length = text.length();
        int i = 0;
        while (i < length) {
            int cp = text.codePointAt(i);
            if (!isTokenChar(cp)) {
                i += Character.charCount(cp);
                continue;
            }
            int start = i;
            int end = scanTokenEnd(text, i);
            emit(text, start, end, sink);
            i = end;
        }
    }

    /** Returns the exclusive end of the token starting at {@code start}. */
    private static int scanTokenEnd(String text, int start) {
        int length = text.length();
        int i = start;
        while (i < length) {
            int cp = text.codePointAt(i);
            if (isTokenChar(cp)) {
                i += Character.charCount(cp);
            } else if (isApostrophe(cp) && i + 1 < length && isTokenChar(text.codePointAt(i + 1))) {
                i += 1;
            } else {
                break;
            }
        }
        return i;
    }

    private void emit(String text, int start, int end, SpanSink sink) {
        String term = normalize(text, start, end);
        if (term != null && !stopWords.contains(term)) {
            sink.accept(term, start, end);
        }
    }

    /** Normalizes text[start, end) or returns null if the token must be dropped. */
    static String normalize(String text, int start, int end) {
        StringBuilder out = new StringBuilder(Math.min(end - start, MAX_TOKEN_LENGTH + 1));
        boolean ascii = true;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) >= 0x80) {
                ascii = false;
                break;
            }
        }
        if (ascii) {
            for (int i = start; i < end; i++) {
                char c = text.charAt(i);
                if (c == '\'') {
                    continue;
                }
                out.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
                if (out.length() > MAX_TOKEN_LENGTH) {
                    return null;
                }
            }
            return out.isEmpty() ? null : out.toString();
        }
        String decomposed = Normalizer.normalize(text.substring(start, end), Normalizer.Form.NFKD);
        int codePoints = 0;
        for (int i = 0; i < decomposed.length(); ) {
            int cp = decomposed.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                out.appendCodePoint(Character.toLowerCase(cp));
                if (++codePoints > MAX_TOKEN_LENGTH) {
                    return null;
                }
            }
        }
        return out.isEmpty() ? null : out.toString();
    }

    private static boolean isTokenChar(int cp) {
        if (cp < 0x80) {
            return (cp >= 'a' && cp <= 'z') || (cp >= 'A' && cp <= 'Z') || (cp >= '0' && cp <= '9');
        }
        if (Character.isLetterOrDigit(cp)) {
            return true;
        }
        int type = Character.getType(cp);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    private static boolean isApostrophe(int cp) {
        return cp == '\'' || cp == '’';
    }
}
