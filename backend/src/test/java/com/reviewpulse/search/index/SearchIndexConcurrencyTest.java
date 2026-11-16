package com.reviewpulse.search.index;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.reviewpulse.search.SearchFixtures;
import com.reviewpulse.search.query.Operator;
import com.reviewpulse.search.query.QueryExecutor;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.ranking.SortOrder;
import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Queries run while a writer inserts, rewrites and deletes documents (enough churn to trigger
 * automatic compaction). Every document contains "common", so within one consistent read the
 * number of hits for "common" must equal the live document count. A torn read would break it.
 */
class SearchIndexConcurrencyTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    void readersAlwaysSeeAtomicBatchesWhileWriterMutatesAndCompacts() throws Exception {
        SearchIndex index = new SearchIndex(Tokenizer.DEFAULT);
        AtomicBoolean done = new AtomicBoolean(false);
        AtomicLong reads = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<?>> readers = new ArrayList<>();
        for (int r = 0; r < 3; r++) {
            readers.add(pool.submit(() -> {
                while (!done.get()) {
                    long[] seen = index.read(idx -> new long[] {
                            QueryExecutor.execute(idx, SearchFixtures.query("common", Operator.OR, SortOrder.RECENCY, 5),
                                    Bm25Parameters.DEFAULT, 5_000).totalHits(),
                            idx.liveDocCount()});
                    assertThat(seen[0]).isEqualTo(seen[1]);
                    reads.incrementAndGet();
                }
                return null;
            }));
        }

        int batches = 300;
        for (int b = 0; b < batches; b++) {
            List<IndexMutation> batch = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                long id = (b * 37L + i) % 4_000;
                long version = b + 1;
                if (i % 10 == 0) {
                    batch.add(new IndexMutation.Delete(id, version));
                } else {
                    batch.add(new IndexMutation.Upsert(SearchFixtures.review(id, version, SearchFixtures.P1,
                            "common title " + b, "common body word" + (i % 50), 1 + i % 5, i, i % 2 == 0, b)));
                }
            }
            index.apply(batch);
        }
        done.set(true);
        for (Future<?> f : readers) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        assertThat(reads.get()).isPositive();
        assertThat(index.compactions()).as("churn should have triggered compaction").isPositive();
        assertThat(index.generation()).isEqualTo(batches);
    }
}
