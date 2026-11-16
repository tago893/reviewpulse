package com.reviewpulse.search.tokenizer;

import java.util.Set;

/**
 * Stop words removed at index and query time.
 *
 * <p>Based on Lucene's classic English stop set, with two deliberate changes for review search:
 * <ul>
 *   <li>"no" and "not" are KEPT: "not greasy" and "greasy" mean opposite things in a review.</li>
 *   <li>First/second-person pronouns ("i", "my", "me", "you", "your", "we") are ADDED: they appear in
 *       most reviews, carry no topical signal, and would otherwise create posting lists covering
 *       the majority of the corpus.</li>
 * </ul>
 * Changing this list changes {@link Tokenizer#FINGERPRINT}, which invalidates persisted snapshots.
 */
public final class StopWords {

    public static final Set<String> ENGLISH = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "if", "in", "into", "is",
            "it", "of", "on", "or", "such", "that", "the", "their", "then", "there", "these", "they",
            "this", "to", "was", "will", "with",
            "i", "me", "my", "we", "you", "your");

    private StopWords() {
    }
}
