package com.reviewpulse.search.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.reviewpulse.search.SearchFixtures;
import com.reviewpulse.search.SnapshotStore;
import com.reviewpulse.search.query.Hit;
import com.reviewpulse.search.query.Operator;
import com.reviewpulse.search.query.QueryExecutor;
import com.reviewpulse.search.query.SearchFilters;
import com.reviewpulse.search.query.SearchQuery;
import com.reviewpulse.search.ranking.Bm25Parameters;
import com.reviewpulse.search.ranking.SortOrder;
import com.reviewpulse.search.tokenizer.Tokenizer;

class IndexSnapshotCodecTest {

    private static final IndexSnapshotCodec.Header HEADER = new IndexSnapshotCodec.Header(
            Tokenizer.FINGERPRINT, "topic-123", 7, 1_700_000_000_000L, 42, Map.of(0, 10L, 1, 0L, 2, 99L));

    private static InvertedIndex churnedIndex() {
        InvertedIndex index = new InvertedIndex(Tokenizer.DEFAULT);
        Random random = new Random(11);
        String[] words = {"dry", "skin", "smooth", "greasy", "scent", "hair", "café", "not", "good", "fine"};
        for (int i = 1; i <= 500; i++) {
            index.upsert(SearchFixtures.review(i, 1, i % 3 == 0 ? SearchFixtures.P1 : SearchFixtures.P2,
                    words[random.nextInt(words.length)], words[random.nextInt(words.length)] + " "
                            + words[random.nextInt(words.length)], 1 + random.nextInt(5), random.nextInt(9),
                    random.nextBoolean(), random.nextInt(1_000)));
        }
        for (int i = 1; i <= 100; i++) {
            index.upsert(SearchFixtures.review(i, 2, SearchFixtures.P1, "rewritten", "smooth finish " + i, 4, 1, true, i));
        }
        for (int i = 101; i <= 150; i++) {
            index.delete(i, 2);
        }
        index.delete(9_999, 5);
        return index.compact();
    }

    private static byte[] encode(InvertedIndex index) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IndexSnapshotCodec.write(index, HEADER, out);
        return out.toByteArray();
    }

    private static List<Long> run(InvertedIndex index, String q, Operator op, SearchFilters f, SortOrder s) {
        return QueryExecutor.execute(index, new SearchQuery(Tokenizer.DEFAULT.queryTerms(q), op, f, s, 50, null),
                Bm25Parameters.DEFAULT, 1_000).hits().stream().map(Hit::reviewId).toList();
    }

    @Test
    void roundTripPreservesHeaderStatisticsVersionsAndSearchResults() throws IOException {
        InvertedIndex original = churnedIndex();
        IndexSnapshotCodec.Loaded loaded = IndexSnapshotCodec.read(new ByteArrayInputStream(encode(original)), Tokenizer.DEFAULT);
        InvertedIndex restored = loaded.index();

        assertThat(loaded.header()).isEqualTo(HEADER);
        assertThat(restored.stats()).isEqualTo(original.stats());
        assertThat(restored.termList()).isEqualTo(original.termList());
        int terms = original.termList().size();
        assertThat(Arrays.copyOf(restored.documentFrequencies(), terms)).isEqualTo(Arrays.copyOf(original.documentFrequencies(), terms));
        assertThat(restored.versionMap()).isEqualTo(original.versionMap());
        assertThat(restored.appliedVersion(9_999)).hasValue(5);
        assertThat(restored.averageBodyLength()).isEqualTo(original.averageBodyLength());

        for (SortOrder sort : SortOrder.values()) {
            for (Operator op : Operator.values()) {
                for (SearchFilters f : List.of(SearchFilters.NONE, new SearchFilters(4, 5, SearchFixtures.P1, true))) {
                    assertThat(run(restored, "smooth dry cafe", op, f, sort)).isEqualTo(run(original, "smooth dry cafe", op, f, sort));
                }
            }
        }
        // The restored index keeps accepting version-checked mutations.
        assertThat(restored.upsert(SearchFixtures.review(1, 2, SearchFixtures.P1, "x", "y", 3, 0, true, 1)))
                .isEqualTo(ApplyOutcome.STALE_IGNORED);
        assertThat(restored.upsert(SearchFixtures.review(1, 3, SearchFixtures.P1, "x", "brandnew", 3, 0, true, 1)))
                .isEqualTo(ApplyOutcome.CONTENT_UPDATED);
        assertThat(run(restored, "brandnew", Operator.OR, SearchFilters.NONE, SortOrder.RELEVANCE)).containsExactly(1L);
    }

    @Test
    void detectsBitFlipsTruncationAndForeignFiles() throws IOException {
        byte[] bytes = encode(churnedIndex());
        Map<String, byte[]> damaged = new HashMap<>();
        byte[] flipped = bytes.clone();
        flipped[bytes.length / 2] ^= 0x10;
        damaged.put("bit flip", flipped);
        damaged.put("truncated", Arrays.copyOf(bytes, bytes.length - 9));
        damaged.put("empty", new byte[0]);
        byte[] wrongMagic = bytes.clone();
        wrongMagic[0] = 'X';
        damaged.put("wrong magic", wrongMagic);
        for (Map.Entry<String, byte[]> e : damaged.entrySet()) {
            assertThatThrownBy(() -> IndexSnapshotCodec.read(new ByteArrayInputStream(e.getValue()), Tokenizer.DEFAULT))
                    .as(e.getKey()).isInstanceOf(IndexSnapshotCodec.CorruptSnapshotException.class);
        }
    }

    @Test
    void refusesToSnapshotAnUncompactedIndex() {
        InvertedIndex index = new InvertedIndex(Tokenizer.DEFAULT);
        index.upsert(SearchFixtures.review(1, 1, SearchFixtures.P1, "a", "b", 3, 0, true, 1));
        index.delete(1, 2);
        assertThatThrownBy(() -> IndexSnapshotCodec.write(index, HEADER, new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotStoreWritesAtomicallyAndReportsMissingAndCorruptFiles(@TempDir Path dir) throws IOException {
        SnapshotStore store = new SnapshotStore(dir.resolve("index"), Tokenizer.DEFAULT);
        assertThat(store.load()).isInstanceOf(SnapshotStore.Missing.class);

        InvertedIndex index = churnedIndex();
        long bytes = store.write(index, HEADER);
        assertThat(bytes).isPositive();
        assertThat(Files.exists(store.file().resolveSibling("index.snapshot.tmp"))).isFalse();
        SnapshotStore.LoadResult loaded = store.load();
        assertThat(loaded).isInstanceOf(SnapshotStore.Found.class);
        assertThat(((SnapshotStore.Found) loaded).snapshot().index().stats()).isEqualTo(index.stats());

        Files.write(store.file(), new byte[] {1, 2, 3});
        assertThat(store.load()).isInstanceOf(SnapshotStore.Corrupt.class);
    }

    @Test
    void writeFailsCleanlyWhenTheDirectoryIsUnusable(@TempDir Path dir) throws IOException {
        Path notADirectory = dir.resolve("blocker");
        Files.writeString(notADirectory, "a regular file where the snapshot directory should be");
        SnapshotStore store = new SnapshotStore(notADirectory.resolve("index"), Tokenizer.DEFAULT);
        assertThatThrownBy(() -> store.write(churnedIndex(), HEADER)).isInstanceOf(IOException.class);
    }
}
