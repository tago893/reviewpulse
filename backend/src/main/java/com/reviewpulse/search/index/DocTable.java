package com.reviewpulse.search.index;

import java.util.Arrays;
import java.util.BitSet;

/**
 * Per-document attributes stored column-wise in primitive arrays, indexed by internal docId.
 *
 * <p>Column storage keeps filters and sort keys cache-friendly and avoids one object per document.
 * A docId is never reused; when a review's content changes it gets a new docId and the old one is
 * marked dead in {@link #live}.
 */
final class DocTable {

    private static final int INITIAL_CAPACITY = 1024;

    private int size;
    private long[] reviewIds = new long[INITIAL_CAPACITY];
    private long[] versions = new long[INITIAL_CAPACITY];
    private int[] titleLengths = new int[INITIAL_CAPACITY];
    private int[] bodyLengths = new int[INITIAL_CAPACITY];
    private byte[] ratings = new byte[INITIAL_CAPACITY];
    private int[] helpfulVotes = new int[INITIAL_CAPACITY];
    private int[] productIds = new int[INITIAL_CAPACITY];
    private long[] reviewedAt = new long[INITIAL_CAPACITY];
    private long[] contentHashHi = new long[INITIAL_CAPACITY];
    private long[] contentHashLo = new long[INITIAL_CAPACITY];
    private int[][] termIds = new int[INITIAL_CAPACITY][];
    private final BitSet verified = new BitSet();
    private final BitSet live = new BitSet();

    int append(IndexedReview review, int productId, int titleLength, int bodyLength,
               long hashHi, long hashLo, int[] sortedTermIds) {
        return appendRaw(review.reviewId(), review.version(), titleLength, bodyLength, review.rating(),
                review.helpfulVotes(), review.verifiedPurchase(), productId, review.reviewedAtEpochMillis(),
                hashHi, hashLo, sortedTermIds);
    }

    /** Appends a live document from raw column values (compaction and snapshot loading). */
    int appendRaw(long reviewId, long version, int titleLength, int bodyLength, int rating, int helpful,
                  boolean isVerified, int productId, long reviewedAtMillis, long hashHi, long hashLo,
                  int[] sortedTermIds) {
        ensureCapacity(size + 1);
        int docId = size++;
        reviewIds[docId] = reviewId;
        titleLengths[docId] = titleLength;
        bodyLengths[docId] = bodyLength;
        productIds[docId] = productId;
        contentHashHi[docId] = hashHi;
        contentHashLo[docId] = hashLo;
        termIds[docId] = sortedTermIds;
        setMetadata(docId, version, rating, helpful, reviewedAtMillis, isVerified);
        live.set(docId);
        return docId;
    }

    void setMetadata(int docId, IndexedReview review) {
        setMetadata(docId, review.version(), review.rating(), review.helpfulVotes(),
                review.reviewedAtEpochMillis(), review.verifiedPurchase());
    }

    private void setMetadata(int docId, long version, int rating, int helpful, long reviewedAtMillis,
                             boolean isVerified) {
        versions[docId] = version;
        ratings[docId] = (byte) rating;
        helpfulVotes[docId] = helpful;
        reviewedAt[docId] = reviewedAtMillis;
        verified.set(docId, isVerified);
    }

    void kill(int docId) {
        live.clear(docId);
        termIds[docId] = null;
    }

    private void ensureCapacity(int needed) {
        if (needed <= reviewIds.length) {
            return;
        }
        int cap = Math.max(needed, reviewIds.length + (reviewIds.length >> 1));
        reviewIds = Arrays.copyOf(reviewIds, cap);
        versions = Arrays.copyOf(versions, cap);
        titleLengths = Arrays.copyOf(titleLengths, cap);
        bodyLengths = Arrays.copyOf(bodyLengths, cap);
        ratings = Arrays.copyOf(ratings, cap);
        helpfulVotes = Arrays.copyOf(helpfulVotes, cap);
        productIds = Arrays.copyOf(productIds, cap);
        reviewedAt = Arrays.copyOf(reviewedAt, cap);
        contentHashHi = Arrays.copyOf(contentHashHi, cap);
        contentHashLo = Arrays.copyOf(contentHashLo, cap);
        termIds = Arrays.copyOf(termIds, cap);
    }

    int size() {
        return size;
    }

    boolean isLive(int docId) {
        return live.get(docId);
    }

    long reviewId(int docId) {
        return reviewIds[docId];
    }

    long version(int docId) {
        return versions[docId];
    }

    int titleLength(int docId) {
        return titleLengths[docId];
    }

    int bodyLength(int docId) {
        return bodyLengths[docId];
    }

    int rating(int docId) {
        return ratings[docId];
    }

    int helpfulVotes(int docId) {
        return helpfulVotes[docId];
    }

    boolean verified(int docId) {
        return verified.get(docId);
    }

    int productId(int docId) {
        return productIds[docId];
    }

    long reviewedAt(int docId) {
        return reviewedAt[docId];
    }

    boolean sameContent(int docId, long hashHi, long hashLo) {
        return contentHashHi[docId] == hashHi && contentHashLo[docId] == hashLo;
    }

    long contentHashHi(int docId) {
        return contentHashHi[docId];
    }

    long contentHashLo(int docId) {
        return contentHashLo[docId];
    }

    int[] termIds(int docId) {
        return termIds[docId];
    }

    long approximateBytes() {
        long perDoc = 8L * 5 + 4L * 4 + 1 + 8;
        long forward = 0;
        for (int i = 0; i < size; i++) {
            int[] t = termIds[i];
            forward += t == null ? 0 : 16 + 4L * t.length;
        }
        return perDoc * reviewIds.length + forward + (verified.size() + live.size()) / 8;
    }
}
