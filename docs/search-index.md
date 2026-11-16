# The custom inverted index

All retrieval and ranking code is in `backend/src/main/java/com/reviewpulse/search` and has no
dependency on Spring, Lucene, Elasticsearch or PostgreSQL full-text search.

## Tokenizer (`tokenizer/Tokenizer.java`)

Identical for documents and queries:

1. Tokens are maximal runs of Unicode letters/digits/combining marks; everything else separates.
2. An apostrophe between letters joins the parts: `don't` → `dont`.
3. Non-ASCII tokens: NFKD, strip combining marks, keep letters/digits: `Café` → `cafe`, `ﬁne` → `fine`,
   full-width `ＡＢＣ` → `abc`. ASCII tokens take a fast path with no `Normalizer` call.
4. Locale-independent lowercase; tokens over 40 code points are dropped.
5. A small stop list: Lucene's classic English set **minus "no"/"not"** (negation matters in reviews:
   *not greasy*) **plus pronouns** ("i", "my", "you", …) that occur in most reviews.

The rule set has a fingerprint stored in checkpoints; changing it forces a rebuild. Limitation: scripts
without spaces (CJK, Thai) are not segmented, and there is no stemming (`moisturizer` ≠ `moisturizing`).

## Data structures

```
term dictionary   HashMap<String,int termId>, ArrayList<String>
postings[termId]  PostingList: int[] docIds (ascending) + int[] packed(titleTf<<16 | bodyTf)
df[termId]        live documents containing the term (exact)
product postings  same structure keyed by parent ASIN (tf = 0)
doc table         column arrays by docId: reviewId, version, title/body length, rating, helpful,
                  verified (BitSet), productId, reviewedAt, 128-bit content hash, sorted termIds, live (BitSet)
maps              reviewId → live docId, reviewId → last applied version (incl. tombstones)
```

* **docIds are never reused.** A content change appends a new docId, so posting lists stay sorted with
  O(1) amortized appends. The old docId is marked dead (lazy deletion, like Lucene segments).
* **Exact statistics.** Each doc keeps its sorted term ids, so deleting it decrements `df` precisely, and
  live length sums are maintained. BM25 never drifts between compactions.
* **Metadata-only updates** (helpful votes, rating, verified, timestamp) are detected by comparing the
  content hash. They update the doc table in place and touch no postings. Ratings filter and helpfulness
  sorts read the doc table, so they reflect updates immediately.
* **Compaction** copies live docs into dense new docIds (order-preserving, so postings stay sorted) and
  drops dead postings and unused terms. It is triggered when ≥ 25% of slots and ≥ 5,000 docs are dead,
  and before every checkpoint. It is built under the read lock and swapped under a brief write lock.

## Query execution (`query/QueryExecutor.java`)

| Case | Strategy | Cost (k terms, m = shortest list, n = longest, N = total postings) |
|---|---|---|
| AND | leapfrog intersection from the shortest list, galloping `advance()` in the others | O(k · m · log(n/m)) |
| OR | k-way merge with a binary min-heap of cursors | O(N log k) |
| product filter | the product's posting list drives; each term probed with galloping search | O(p · k · log n) for p product docs |
| rating / verified | doc-table check per candidate | O(1) each |

Galloping (exponential) search lets a short list skip through a long one without scanning it.

**Top-K:** a size-K heap whose head is the worst kept hit, so O(M log K) time and O(K) memory for M
matches. **Keyset cursors** (`after` = last sort key) let page 50 cost the same as page 1. The cursor is
base64url of `(fingerprint, score bits, timestamp, helpful, reviewId)`; the fingerprint binds it to the
query, filters and sort (a mismatch is a 400).

**Budget:** every 4,096 candidates the executor checks a deadline (2 s default) and fails the query (503)
rather than returning partial results.

## Ranking (`ranking/`)

BM25F, combining fields before saturation:

```
idf(t)   = ln(1 + (N − df + 0.5) / (df + 0.5))
tf~(t,d) = 2.0 · tfTitle / (0.25 + 0.75 · lenTitle/avgTitle) + 1.0 · tfBody / (0.25 + 0.75 · lenBody/avgBody)
score    = Σ_t idf(t) · tf~ · (k1 + 1) / (k1 + tf~)        k1 = 1.2
```

The parameters are standard defaults, not tuned (there are no relevance judgments for this corpus).
Scores are summed in query-term order, so results are bit-for-bit reproducible.

Business sorts are separate total orders over the same matches:

* relevance: score ↓, review id ↑
* recency: reviewedAt ↓, id ↑
* helpfulness: helpful ↓, score ↓, id ↑

Every hit still carries its relevance score.

## Correctness evidence

* `QueryExecutorTest.AgainstReferenceImplementation`: 12 random corpora × 400 mutations (inserts,
  content/metadata updates, deletes, duplicate and stale replays) × 40 random queries (operators,
  filters, sorts, random page sizes). Full paginated results must equal an independent brute-force
  engine in order, with scores within 1e-12, both before and after compaction.
* Hand-labeled fixtures (`SearchFixtures.labeledCorpus`) and hand-computed BM25 values.
* Snapshot round trip: identical results, statistics and tombstones; corruption detected.

## Persistence format (`index/IndexSnapshotCodec.java`)

`RPIX` magic, format version, a header (tokenizer fingerprint, topic id, import run, generation,
per-partition offsets), aggregates, products, doc table (delta-varint term lists), terms with
df and delta-varint postings, the version map, and a CRC32C trailer over everything. The file holds the
compacted index; at 99,688 reviews it is 16.4 MB on disk.
