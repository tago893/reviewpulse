package com.reviewpulse.reviews;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.reviewpulse.api.RequestValidationException;

class ReviewInputTest {

    @Test
    void cleanNormalizesLineEndingsControlCharactersAndUnicode() {
        assertThat(ReviewInput.clean(null)).isEmpty();
        assertThat(ReviewInput.clean("  a\r\nb\rc  ")).isEqualTo("a\nb\nc");
        assertThat(ReviewInput.clean("nul\u0000 bell\u0007 tab\tkept")).isEqualTo("nul bell tab\tkept");
        assertThat(ReviewInput.clean("café")).isEqualTo("café");
    }

    @Test
    void contentAcceptsValidInputAndFlattensTitleNewlines() {
        List<String> problems = new ArrayList<>();
        ReviewInput.Content c = ReviewInput.content("Two\nlines", "Body", 5, problems);
        assertThat(problems).isEmpty();
        assertThat(c).isEqualTo(new ReviewInput.Content("Two lines", "Body", 5));
        ReviewInput.throwIfAny(problems);
    }

    @Test
    void contentReportsEveryProblem() {
        List<String> problems = new ArrayList<>();
        ReviewInput.content("t".repeat(ReviewInput.MAX_TITLE_CHARS + 1), "b".repeat(ReviewInput.MAX_BODY_CHARS + 1), 6, problems);
        assertThat(problems).hasSize(3);

        List<String> empty = new ArrayList<>();
        ReviewInput.content("  ", null, null, empty);
        assertThat(empty).containsExactly("title or body must contain text", "rating must be an integer from 1 to 5");
        assertThatThrownBy(() -> ReviewInput.throwIfAny(empty)).isInstanceOf(RequestValidationException.class);
    }

    @Test
    void parentAsinAndExpectedVersionValidation() {
        List<String> problems = new ArrayList<>();
        assertThat(ReviewInput.parentAsin(" B00YQ6X8EO ", problems)).isEqualTo("B00YQ6X8EO");
        assertThat(problems).isEmpty();
        ReviewInput.parentAsin("b00yq6x8eo", problems);
        ReviewInput.parentAsin(null, problems);
        assertThat(ReviewInput.expectedVersion(3L, problems)).isEqualTo(3);
        ReviewInput.expectedVersion(null, problems);
        ReviewInput.expectedVersion(0L, problems);
        assertThat(problems).hasSize(4);
    }
}
