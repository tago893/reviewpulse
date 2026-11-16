package com.reviewpulse.search;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.reviewpulse.reviews.ReviewRepository;
import com.reviewpulse.search.index.InvertedIndex;
import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Builds a complete index from the authoritative PostgreSQL data. The new index is private until
 * the caller swaps it in, so searches keep using the previous index during a rebuild.
 */
@Component
public class IndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(IndexBuilder.class);
    private static final int PROGRESS_EVERY = 50_000;

    private final ReviewRepository reviews;
    private final Tokenizer tokenizer;

    public IndexBuilder(ReviewRepository reviews, Tokenizer tokenizer) {
        this.reviews = reviews;
        this.tokenizer = tokenizer;
    }

    /** Result of a build: the index plus how many rows went in. */
    public record Built(InvertedIndex index, long rows, long tombstones, long millis) {
    }

    public Built buildFromDatabase() {
        long start = System.nanoTime();
        InvertedIndex index = new InvertedIndex(tokenizer);
        long[] counts = new long[2];
        reviews.streamAll(review -> {
            if (review.deleted()) {
                index.delete(review.id(), review.version());
                counts[1]++;
            } else {
                index.upsert(review.toIndexed());
            }
            if (++counts[0] % PROGRESS_EVERY == 0) {
                log.info("index build progress: {} rows", counts[0]);
            }
        });
        long millis = (System.nanoTime() - start) / 1_000_000;
        log.info("index built from PostgreSQL: rows={} live={} tombstones={} terms={} in {} ms",
                counts[0], index.liveDocCount(), counts[1], index.stats().terms(), millis);
        return new Built(index, counts[0], counts[1], millis);
    }
}
