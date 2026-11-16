package com.reviewpulse.search.index;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Function;

import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Thread-safe owner of the current {@link InvertedIndex}.
 *
 * <ul>
 *   <li>Queries run under the shared read lock and see a batch either entirely or not at all.</li>
 *   <li>Writers are serialized by {@code writerMutex}; the exclusive write lock is held only while
 *       a batch is applied or a new index instance is swapped in.</li>
 *   <li>Compaction and snapshotting read the index under the read lock: because the writer mutex is
 *       held, nothing mutates meanwhile, and queries keep running.</li>
 *   <li>{@link #generation()} increases whenever visible search results may have changed. Caches
 *       include it in their keys, so a cached result is never served across an index change.</li>
 * </ul>
 */
public final class SearchIndex {

    /** Compact when at least this fraction of doc slots are dead... */
    static final double COMPACTION_DEAD_RATIO = 0.25;
    /** ...and at least this many dead docs exist (avoids churn on tiny indexes). */
    static final int COMPACTION_MIN_DEAD = 5_000;

    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final ReentrantLock writerMutex = new ReentrantLock();
    private InvertedIndex current;
    private volatile long generation;
    private volatile long compactions;

    public SearchIndex(Tokenizer tokenizer) {
        this.current = new InvertedIndex(tokenizer);
    }

    /** Runs {@code reader} against a consistent view of the index. */
    public <T> T read(Function<InvertedIndex, T> reader) {
        lock.readLock().lock();
        try {
            return reader.apply(current);
        } finally {
            lock.readLock().unlock();
        }
    }

    /** Applies mutations atomically with respect to readers. */
    public BatchResult apply(List<IndexMutation> mutations) {
        writerMutex.lock();
        try {
            List<ApplyOutcome> outcomes = new ArrayList<>(mutations.size());
            lock.writeLock().lock();
            try {
                for (IndexMutation m : mutations) {
                    outcomes.add(switch (m) {
                        case IndexMutation.Upsert u -> current.upsert(u.review());
                        case IndexMutation.Delete d -> current.delete(d.reviewId(), d.version());
                    });
                }
            } finally {
                lock.writeLock().unlock();
            }
            BatchResult result = new BatchResult(outcomes);
            if (result.changedIndex()) {
                generation++;
            }
            compactIfNeeded();
            return result;
        } finally {
            writerMutex.unlock();
        }
    }

    /** Atomically replaces the whole index (rebuild or snapshot load). */
    public void replace(InvertedIndex fresh) {
        writerMutex.lock();
        try {
            lock.writeLock().lock();
            try {
                current = fresh;
                generation++;
            } finally {
                lock.writeLock().unlock();
            }
        } finally {
            writerMutex.unlock();
        }
    }

    /**
     * Runs {@code task} with writers excluded but readers allowed (snapshotting).
     * The task must not mutate the index.
     */
    public <T> T withWritersPaused(Function<InvertedIndex, T> task) {
        writerMutex.lock();
        try {
            return read(task);
        } finally {
            writerMutex.unlock();
        }
    }

    private void compactIfNeeded() {
        IndexStats stats = read(InvertedIndex::stats);
        if (stats.deadDocs() >= COMPACTION_MIN_DEAD && stats.deadRatio() >= COMPACTION_DEAD_RATIO) {
            forceCompaction();
        }
    }

    /** Compacts now. Builds the copy under the read lock, swaps under the write lock. */
    public void forceCompaction() {
        writerMutex.lock();
        try {
            InvertedIndex compacted = read(InvertedIndex::compact);
            lock.writeLock().lock();
            try {
                current = compacted;
            } finally {
                lock.writeLock().unlock();
            }
            compactions++;
        } finally {
            writerMutex.unlock();
        }
    }

    /**
     * Compacts if there are dead documents and returns the (now compact) current index for
     * serialization. Only the single writer may call this, and it must not mutate meanwhile;
     * concurrent readers are fine because nothing mutates the returned instance.
     */
    public InvertedIndex compactedForSnapshot() {
        writerMutex.lock();
        try {
            if (read(InvertedIndex::stats).deadDocs() > 0) {
                forceCompaction();
            }
            return read(idx -> idx);
        } finally {
            writerMutex.unlock();
        }
    }

    public long generation() {
        return generation;
    }

    public long compactions() {
        return compactions;
    }

    public IndexStats stats() {
        return read(InvertedIndex::stats);
    }
}
