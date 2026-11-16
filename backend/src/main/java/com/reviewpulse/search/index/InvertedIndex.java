package com.reviewpulse.search.index;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import com.reviewpulse.search.postings.PostingList;
import com.reviewpulse.search.tokenizer.Tokenizer;

/**
 * Custom inverted index: term -> posting list of (docId, titleTf, bodyTf), plus a column-store
 * doc table and exact corpus statistics for BM25.
 *
 * <h2>Update model</h2>
 * <ul>
 *   <li>Every review has a monotonically increasing version. A mutation is applied only if its
 *       version is greater than the last applied version for that review, which makes duplicate and
 *       out-of-order events no-ops. Versions of deleted reviews are kept (tombstones).</li>
 *   <li>Content changes allocate a new docId; the old docId is marked dead (lazy deletion) and
 *       its postings are dropped at {@link #compact()} time. Posting lists therefore stay sorted
 *       append-only arrays.</li>
 *   <li>Metadata-only changes (helpful votes, rating, verified flag, timestamp) update the doc table
 *       in place and never touch postings.</li>
 *   <li>Document frequency, live document count and field-length sums exclude dead docs at all
 *       times (a per-doc forward list of term ids makes exact decrements possible), so BM25
 *       statistics never drift between compactions.</li>
 * </ul>
 *
 * <p><b>Not thread-safe.</b> {@code SearchIndex} wraps it with a read/write lock and a single writer.
 */
public final class InvertedIndex {

    private final Tokenizer tokenizer;
    private final Map<String, Integer> termIds = new HashMap<>();
    private final List<String> terms = new ArrayList<>();
    private final List<PostingList> postings = new ArrayList<>();
    private int[] documentFrequency = new int[1024];

    private final Map<String, Integer> productIds = new HashMap<>();
    private final List<String> products = new ArrayList<>();
    private final List<PostingList> productPostings = new ArrayList<>();

    private final DocTable docs = new DocTable();
    private final Map<Long, Integer> liveDocByReview = new HashMap<>();
    private final Map<Long, Long> versionByReview = new HashMap<>();

    private int liveDocs;
    private long liveTitleLengthSum;
    private long liveBodyLengthSum;
    private final MessageDigest sha256;

    public InvertedIndex(Tokenizer tokenizer) {
        this.tokenizer = tokenizer;
        try {
            this.sha256 = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    // ------------------------------------------------------------------ mutations

    /** Inserts or replaces the searchable state of a review, if {@code review} is newer. */
    public ApplyOutcome upsert(IndexedReview review) {
        Long known = versionByReview.get(review.reviewId());
        if (known != null && known >= review.version()) {
            return ApplyOutcome.STALE_IGNORED;
        }
        long[] hash = contentHash(review);
        Integer existing = liveDocByReview.get(review.reviewId());
        if (existing != null && docs.sameContent(existing, hash[0], hash[1])) {
            docs.setMetadata(existing, review);
            versionByReview.put(review.reviewId(), review.version());
            return ApplyOutcome.METADATA_UPDATED;
        }
        if (existing != null) {
            retire(existing);
        }
        addDocument(review, hash);
        versionByReview.put(review.reviewId(), review.version());
        return existing == null ? ApplyOutcome.INSERTED : ApplyOutcome.CONTENT_UPDATED;
    }

    /** Removes a review from search, if {@code version} is newer than what the index has. */
    public ApplyOutcome delete(long reviewId, long version) {
        Long known = versionByReview.get(reviewId);
        if (known != null && known >= version) {
            return ApplyOutcome.STALE_IGNORED;
        }
        versionByReview.put(reviewId, version);
        Integer existing = liveDocByReview.remove(reviewId);
        if (existing == null) {
            return ApplyOutcome.TOMBSTONE_RECORDED;
        }
        retire(existing);
        return ApplyOutcome.DELETED;
    }

    private void addDocument(IndexedReview review, long[] hash) {
        Analysis analysis = analyze(review.title(), review.body());
        int productId = productIdOrCreate(review.parentAsin());
        int docId = docs.append(review, productId, analysis.titleLength, analysis.bodyLength,
                hash[0], hash[1], analysis.termIds);
        for (int i = 0; i < analysis.termIds.length; i++) {
            int termId = analysis.termIds[i];
            postings.get(termId).append(docId, analysis.titleTf[i], analysis.bodyTf[i]);
            documentFrequency[termId]++;
        }
        productPostings.get(productId).append(docId, 0, 0);
        liveDocByReview.put(review.reviewId(), docId);
        liveDocs++;
        liveTitleLengthSum += analysis.titleLength;
        liveBodyLengthSum += analysis.bodyLength;
    }

    private void retire(int docId) {
        for (int termId : docs.termIds(docId)) {
            documentFrequency[termId]--;
        }
        liveDocs--;
        liveTitleLengthSum -= docs.titleLength(docId);
        liveBodyLengthSum -= docs.bodyLength(docId);
        docs.kill(docId);
    }

    /** Per-document term statistics; termIds sorted ascending with aligned frequencies. */
    private record Analysis(int[] termIds, int[] titleTf, int[] bodyTf, int titleLength, int bodyLength) {
    }

    private Analysis analyze(String title, String body) {
        Map<String, int[]> counts = new HashMap<>();
        int[] lengths = new int[2];
        tokenizer.forEachTerm(title, term -> {
            counts.computeIfAbsent(term, k -> new int[2])[0]++;
            lengths[0]++;
        });
        tokenizer.forEachTerm(body, term -> {
            counts.computeIfAbsent(term, k -> new int[2])[1]++;
            lengths[1]++;
        });
        long[] packed = new long[counts.size()];
        int n = 0;
        for (Map.Entry<String, int[]> e : counts.entrySet()) {
            int termId = termIdOrCreate(e.getKey());
            int t = Math.min(e.getValue()[0], PostingList.MAX_TF);
            int b = Math.min(e.getValue()[1], PostingList.MAX_TF);
            packed[n++] = ((long) termId << 32) | ((long) t << 16) | b;
        }
        Arrays.sort(packed);
        int[] ids = new int[n];
        int[] tt = new int[n];
        int[] bt = new int[n];
        for (int i = 0; i < n; i++) {
            ids[i] = (int) (packed[i] >>> 32);
            tt[i] = (int) ((packed[i] >>> 16) & 0xFFFF);
            bt[i] = (int) (packed[i] & 0xFFFF);
        }
        return new Analysis(ids, tt, bt, lengths[0], lengths[1]);
    }

    private int termIdOrCreate(String term) {
        Integer id = termIds.get(term);
        if (id != null) {
            return id;
        }
        int newId = terms.size();
        termIds.put(term, newId);
        terms.add(term);
        postings.add(new PostingList());
        if (newId == documentFrequency.length) {
            documentFrequency = Arrays.copyOf(documentFrequency, newId + (newId >> 1) + 1);
        }
        return newId;
    }

    private int productIdOrCreate(String parentAsin) {
        Integer id = productIds.get(parentAsin);
        if (id != null) {
            return id;
        }
        int newId = products.size();
        productIds.put(parentAsin, newId);
        products.add(parentAsin);
        productPostings.add(new PostingList());
        return newId;
    }

    private long[] contentHash(IndexedReview review) {
        sha256.reset();
        sha256.update(review.parentAsin().getBytes(StandardCharsets.UTF_8));
        sha256.update((byte) 0);
        sha256.update(review.title().getBytes(StandardCharsets.UTF_8));
        sha256.update((byte) 0);
        sha256.update(review.body().getBytes(StandardCharsets.UTF_8));
        byte[] d = sha256.digest();
        return new long[] {toLong(d, 0), toLong(d, 8)};
    }

    private static long toLong(byte[] bytes, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (bytes[offset + i] & 0xFFL);
        }
        return value;
    }

    // ------------------------------------------------------------------ read API (query execution)

    public Tokenizer tokenizer() {
        return tokenizer;
    }

    public Optional<PostingList> postings(String term) {
        Integer id = termIds.get(term);
        return id == null ? Optional.empty() : Optional.of(postings.get(id));
    }

    public int documentFrequency(String term) {
        Integer id = termIds.get(term);
        return id == null ? 0 : documentFrequency[id];
    }

    public Optional<PostingList> productPostings(String parentAsin) {
        Integer id = productIds.get(parentAsin);
        return id == null ? Optional.empty() : Optional.of(productPostings.get(id));
    }

    public OptionalLong appliedVersion(long reviewId) {
        Long v = versionByReview.get(reviewId);
        return v == null ? OptionalLong.empty() : OptionalLong.of(v);
    }

    public OptionalInt liveDocId(long reviewId) {
        Integer d = liveDocByReview.get(reviewId);
        return d == null ? OptionalInt.empty() : OptionalInt.of(d);
    }

    public boolean isLive(int docId) {
        return docs.isLive(docId);
    }

    public long reviewId(int docId) {
        return docs.reviewId(docId);
    }

    public long version(int docId) {
        return docs.version(docId);
    }

    public int titleLength(int docId) {
        return docs.titleLength(docId);
    }

    public int bodyLength(int docId) {
        return docs.bodyLength(docId);
    }

    public int rating(int docId) {
        return docs.rating(docId);
    }

    public int helpfulVotes(int docId) {
        return docs.helpfulVotes(docId);
    }

    public boolean verifiedPurchase(int docId) {
        return docs.verified(docId);
    }

    public long reviewedAt(int docId) {
        return docs.reviewedAt(docId);
    }

    public String parentAsin(int docId) {
        return products.get(docs.productId(docId));
    }

    public int liveDocCount() {
        return liveDocs;
    }

    public int docSlots() {
        return docs.size();
    }

    public double averageTitleLength() {
        return liveDocs == 0 ? 0.0 : (double) liveTitleLengthSum / liveDocs;
    }

    public double averageBodyLength() {
        return liveDocs == 0 ? 0.0 : (double) liveBodyLengthSum / liveDocs;
    }

    /** All review ids with a live document, for consistency checks. Allocates; not for hot paths. */
    public Map<Long, Long> liveReviewVersions() {
        Map<Long, Long> out = new HashMap<>(liveDocByReview.size() * 2);
        liveDocByReview.forEach((reviewId, docId) -> out.put(reviewId, docs.version(docId)));
        return out;
    }

    public IndexStats stats() {
        long postingCount = 0;
        long bytes = docs.approximateBytes();
        for (PostingList p : postings) {
            postingCount += p.size();
            bytes += 32 + p.allocatedBytes();
        }
        for (PostingList p : productPostings) {
            bytes += 32 + p.allocatedBytes();
        }
        // HashMap entries (~48-64 bytes each) for terms, products and the two review maps.
        bytes += 64L * (termIds.size() + productIds.size()) + 56L * (liveDocByReview.size() + versionByReview.size());
        return new IndexStats(liveDocs, docs.size(), terms.size(), postingCount, products.size(),
                versionByReview.size(), bytes);
    }

    // ------------------------------------------------------------------ compaction

    /**
     * Returns a new index with dead documents removed, docIds renumbered densely (order preserved)
     * and unused terms dropped. Search results and statistics are identical before and after.
     */
    public InvertedIndex compact() {
        InvertedIndex out = new InvertedIndex(tokenizer);
        int[] docMap = new int[docs.size()];
        Arrays.fill(docMap, -1);
        int[] termMap = remapTerms(out);
        int[] productMap = remapProducts(out);
        for (int oldDoc = 0; oldDoc < docs.size(); oldDoc++) {
            if (!docs.isLive(oldDoc)) {
                continue;
            }
            int[] oldTerms = docs.termIds(oldDoc);
            int[] newTerms = new int[oldTerms.length];
            for (int i = 0; i < oldTerms.length; i++) {
                newTerms[i] = termMap[oldTerms[i]];
            }
            docMap[oldDoc] = out.docs.appendRaw(docs.reviewId(oldDoc), docs.version(oldDoc),
                    docs.titleLength(oldDoc), docs.bodyLength(oldDoc), docs.rating(oldDoc),
                    docs.helpfulVotes(oldDoc), docs.verified(oldDoc), productMap[docs.productId(oldDoc)],
                    docs.reviewedAt(oldDoc), docs.contentHashHi(oldDoc), docs.contentHashLo(oldDoc), newTerms);
            out.liveDocByReview.put(docs.reviewId(oldDoc), docMap[oldDoc]);
        }
        copyPostings(postings, termMap, out.postings, docMap);
        copyPostings(productPostings, productMap, out.productPostings, docMap);
        for (int t = 0; t < termMap.length; t++) {
            if (termMap[t] >= 0) {
                out.documentFrequency[termMap[t]] = documentFrequency[t];
            }
        }
        out.versionByReview.putAll(versionByReview);
        out.liveDocs = liveDocs;
        out.liveTitleLengthSum = liveTitleLengthSum;
        out.liveBodyLengthSum = liveBodyLengthSum;
        return out;
    }

    private int[] remapTerms(InvertedIndex out) {
        int[] termMap = new int[terms.size()];
        for (int t = 0; t < terms.size(); t++) {
            termMap[t] = documentFrequency[t] > 0 ? out.termIdOrCreate(terms.get(t)) : -1;
        }
        return termMap;
    }

    private int[] remapProducts(InvertedIndex out) {
        int[] productMap = new int[products.size()];
        Arrays.fill(productMap, -1);
        for (int d = 0; d < docs.size(); d++) {
            if (docs.isLive(d) && productMap[docs.productId(d)] < 0) {
                productMap[docs.productId(d)] = -2;
            }
        }
        for (int p = 0; p < products.size(); p++) {
            if (productMap[p] == -2) {
                productMap[p] = out.productIdOrCreate(products.get(p));
            }
        }
        return productMap;
    }

    private static void copyPostings(List<PostingList> from, int[] idMap, List<PostingList> to, int[] docMap) {
        for (int old = 0; old < from.size(); old++) {
            if (idMap[old] < 0) {
                continue;
            }
            PostingList src = from.get(old);
            PostingList dst = to.get(idMap[old]);
            for (int i = 0; i < src.size(); i++) {
                int newDoc = docMap[src.docAt(i)];
                if (newDoc >= 0) {
                    dst.appendPacked(newDoc, src.packedFreqAt(i));
                }
            }
            dst.trimToSize();
        }
    }

    // ------------------------------------------------------------------ package access for snapshots

    DocTable docTable() {
        return docs;
    }

    List<String> termList() {
        return terms;
    }

    List<PostingList> postingLists() {
        return postings;
    }

    int[] documentFrequencies() {
        return documentFrequency;
    }

    List<String> productList() {
        return products;
    }

    List<PostingList> productPostingLists() {
        return productPostings;
    }

    Map<Long, Long> versionMap() {
        return versionByReview;
    }
}
