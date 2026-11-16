package com.reviewpulse.search.tokenizer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TokenizerTest {

    private final Tokenizer tokenizer = Tokenizer.DEFAULT;

    private List<String> terms(String text) {
        List<String> out = new ArrayList<>();
        tokenizer.forEachTerm(text, out::add);
        return out;
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\n\t", "!!! ... ???", "😀 🎉"})
    void emptyOrSymbolOnlyTextProducesNoTerms(String text) {
        assertThat(terms(text)).isEmpty();
        assertThat(tokenizer.tokenize(text)).isEmpty();
    }

    @Test
    void lowercasesAndSplitsOnPunctuationAndWhitespace() {
        assertThat(terms("Smooth,DRY-skin!!  Moisturizer\n(50ml)"))
                .containsExactly("smooth", "dry", "skin", "moisturizer", "50ml");
    }

    @Test
    void removesStopWordsButKeepsNegations() {
        assertThat(terms("This is NOT the best and it is no good"))
                .containsExactly("not", "best", "no", "good");
    }

    @ParameterizedTest
    @CsvSource({
            "don't, dont",
            "it’s, its",
            "'quoted', quoted",
            "rock'n'roll, rocknroll"})
    void apostrophesInsideWordsJoinTheParts(String input, String expected) {
        assertThat(terms(input)).containsExactly(expected);
    }

    @Test
    @DisplayName("diacritics and compatibility forms fold to ASCII-comparable terms")
    void unicodeNormalization() {
        assertThat(terms("Café NAÏVE crème")).containsExactly("cafe", "naive", "creme");
        assertThat(terms("café")).containsExactly("cafe");
        assertThat(terms("ＡＢＣ ﬁne")).containsExactly("abc", "fine");
        assertThat(terms("Straße İstanbul")).containsExactly("straße", "istanbul");
    }

    @Test
    void indexAndQueryAgreeOnSameInput() {
        String text = "Ultra-HYDRATING Crème — doesn't feel greasy";
        assertThat(tokenizer.queryTerms(text)).containsExactlyElementsOf(terms(text));
    }

    @Test
    void queryTermsAreDistinctInFirstOccurrenceOrder() {
        assertThat(tokenizer.queryTerms("skin dry SKIN dry oily")).containsExactly("skin", "dry", "oily");
    }

    @Test
    void dropsOverlongTokensAndKeepsLimitLengthTokens() {
        String limit = "a".repeat(Tokenizer.MAX_TOKEN_LENGTH);
        String tooLong = "b".repeat(Tokenizer.MAX_TOKEN_LENGTH + 1);
        assertThat(terms(limit + " " + tooLong + " ok")).containsExactly(limit, "ok");
    }

    @Test
    void handlesVeryLongTextInLinearPasses() {
        String text = "smooth skin ".repeat(20_000);
        List<String> out = terms(text);
        assertThat(out).hasSize(40_000);
        assertThat(out.get(39_999)).isEqualTo("skin");
    }

    @Test
    void tokensCarryOriginalOffsetsForHighlighting() {
        String text = "Not GREASY, café!";
        List<Token> tokens = tokenizer.tokenize(text);
        assertThat(tokens).extracting(Token::term).containsExactly("not", "greasy", "cafe");
        Token cafe = tokens.get(2);
        assertThat(text.substring(cafe.start(), cafe.end())).isEqualTo("café");
        assertThat(cafe.position()).isEqualTo(2);
    }

    @Test
    void surrogatePairsAndMixedScriptsAreHandled() {
        assertThat(terms("𝐁𝐨𝐥𝐝 text")).containsExactly("bold", "text");
        assertThat(terms("日本語のテキスト")).hasSize(1);
    }

    @Test
    void fingerprintIsStable() {
        assertThat(Tokenizer.FINGERPRINT).startsWith("rp-tokenizer-v1;maxlen=40;stop=");
    }
}
