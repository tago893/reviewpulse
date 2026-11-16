package com.reviewpulse.search.postings;

import java.util.Arrays;

/**
 * Append-only list of (docId, titleTf, bodyTf) sorted by docId.
 *
 * <p>Sorted order is free: the index assigns docIds monotonically, so every append has a larger
 * docId than all existing entries. Deleted documents are not removed here; the index keeps a
 * live-docs bitset and drops dead entries during compaction (the same model Lucene uses).
 *
 * <p>Memory: 8 bytes per posting (an int docId plus an int holding both term frequencies, each
 * capped at 65,535). Not thread-safe; the owning index serializes writers.
 */
public final class PostingList {

    public static final int MAX_TF = 0xFFFF;
    private static final int INITIAL_CAPACITY = 4;

    private int[] docIds;
    private int[] freqs;
    private int size;

    public PostingList() {
        this(INITIAL_CAPACITY);
    }

    public PostingList(int capacity) {
        int cap = Math.max(1, capacity);
        this.docIds = new int[cap];
        this.freqs = new int[cap];
    }

    /** Appends a posting. docId must be strictly greater than the last appended docId. */
    public void append(int docId, int titleTf, int bodyTf) {
        if (size > 0 && docId <= docIds[size - 1]) {
            throw new IllegalArgumentException(
                    "docIds must be appended in increasing order: " + docId + " after " + docIds[size - 1]);
        }
        if (titleTf < 0 || bodyTf < 0) {
            throw new IllegalArgumentException("term frequencies must be non-negative");
        }
        if (size == docIds.length) {
            int newCap = docIds.length + (docIds.length >> 1) + 1;
            docIds = Arrays.copyOf(docIds, newCap);
            freqs = Arrays.copyOf(freqs, newCap);
        }
        docIds[size] = docId;
        freqs[size] = (Math.min(titleTf, MAX_TF) << 16) | Math.min(bodyTf, MAX_TF);
        size++;
    }

    public int size() {
        return size;
    }

    public int docAt(int index) {
        return docIds[index];
    }

    public int titleTfAt(int index) {
        return freqs[index] >>> 16;
    }

    public int bodyTfAt(int index) {
        return freqs[index] & 0xFFFF;
    }

    public int lastDoc() {
        return size == 0 ? -1 : docIds[size - 1];
    }

    /**
     * Galloping (exponential) search: returns the smallest index i >= from such that
     * docAt(i) >= target, or size() if none.
     *
     * <p>Cost is O(log d) where d is the distance moved, which makes intersecting a short list
     * with a long one O(m log(n/m)) instead of O(n).
     */
    public int advance(int from, int target) {
        if (from >= size || docIds[from] >= target) {
            return from;
        }
        int lo = from;
        int step = 1;
        int hi = from + step;
        while (hi < size && docIds[hi] < target) {
            lo = hi;
            step <<= 1;
            hi = from + step;
        }
        hi = Math.min(hi, size);
        int idx = Arrays.binarySearch(docIds, lo + 1, hi, target);
        return idx >= 0 ? idx : -idx - 1;
    }

    /** Bytes used by the backing arrays (capacity, not size). */
    public long allocatedBytes() {
        return 8L * docIds.length;
    }

    /** Shrinks backing arrays to the exact size. */
    public void trimToSize() {
        if (docIds.length != size) {
            docIds = Arrays.copyOf(docIds, Math.max(1, size));
            freqs = Arrays.copyOf(freqs, Math.max(1, size));
        }
    }

    /** Raw packed frequency, used by snapshot serialization. */
    public int packedFreqAt(int index) {
        return freqs[index];
    }

    /** Appends an already packed frequency (snapshot loading / compaction). */
    public void appendPacked(int docId, int packedFreq) {
        append(docId, packedFreq >>> 16, packedFreq & 0xFFFF);
    }
}
