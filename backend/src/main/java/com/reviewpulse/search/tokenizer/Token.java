package com.reviewpulse.search.tokenizer;

/**
 * A normalized term and where it came from in the original text.
 *
 * @param term     normalized form used for indexing and matching
 * @param start    inclusive char offset in the original text
 * @param end      exclusive char offset in the original text
 * @param position ordinal among the emitted (non-stop-word) tokens
 */
public record Token(String term, int start, int end, int position) {
}
