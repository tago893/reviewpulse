# Interview notes: ReviewPulse design decisions

Short answers you can give out loud, each tied to code or a measurement in this repo.

**Why an inverted index?**
A scan has to read every review for every query: O(N·L). An inverted index precomputes term →
documents, so a query touches only the posting lists of its terms. Measured at 100K reviews: index
p50 0.07 ms vs a PostgreSQL word-boundary scan of 127–267 ms on the same rows (95× to >1,000× by
category). The gap grows with N, because the scan is linear in rows while the index is proportional to
the postings touched.

**How does BM25 work?**
Score = Σ over query terms of idf × saturated term frequency.

* idf = ln(1 + (N − df + 0.5)/(df + 0.5)) rewards rare terms.
* tf saturates as tf·(k1+1)/(tf + k1·norm): the 10th "great" adds far less than the 1st.
* Length normalization (b = 0.75) stops long reviews winning just by being long.

I use BM25F: title and body tf are weighted (title ×2) and length-normalized per field, then combined
before saturation. Parameters are standard defaults; there are no relevance judgments to tune against.

**Complexity of posting-list intersection?**
Naive merge is O(m + n). I use leapfrog intersection starting from the shortest list, with galloping
(exponential then binary) search in the others: O(k · m · log(n/m)) for k lists, shortest m, longest n.
That's why a rare term AND a common term costs about the rare list's length. OR uses a k-way merge with
a min-heap: O(N log k). Top-K is a size-K heap: O(M log K) time, O(K) memory.

**Why Kafka (vs. the indexer polling the outbox)?**
Decoupling and replay: the topic is a durable, ordered (per review) log that many consumers can read at
their own pace. Examples: a second index replica, analytics, a new index version built in parallel. Its
retention lets a crashed indexer resume from offsets instead of rescanning the database. For one
consumer, polling the outbox directly would work; Kafka is what makes it a platform.

**Why a transactional outbox?**
"Write the DB, then publish to Kafka" has two failure modes. The publish can fail after the commit
(lost event), or the publish can succeed while the transaction rolls back (phantom event). Writing the
event row in the same transaction as the review makes them atomic. A relay publishes afterwards: at
least once, marked PUBLISHED only after acks=all. Tested: a rolled-back write publishes nothing.

**How do you prevent stale event processing?**
Every review has a monotonically increasing version (incremented in the same UPDATE), and events carry
it. The indexer applies an event only if its version is greater than the last applied version for that
review; deleted reviews keep a tombstone version. Events are fat (full state), so if v5 arrives before
v4, applying v5 and dropping v4 still yields the correct state. Kafka keys by review id keep per-review
order in the normal case; the version check covers retries and the simulator's injected out-of-order
events.

**What if the consumer crashes?**
Offsets are committed only after a checkpoint (index snapshot + the offsets it reflects) is durable:
temp file, fsync, atomic rename. On restart the indexer loads the checkpoint and replays from its offsets.
Events applied twice are no-ops because of the version check. That gives effectively-once index state on
top of at-least-once delivery, not exactly-once processing. Tested by restoring an older snapshot after
discarding memory.

**How do you rebuild a corrupted index?**
The snapshot has a CRC32C trailer and a header with the tokenizer fingerprint, topic id, partition set
and last bulk import. Any mismatch, corruption, or offsets lost to retention means rebuild from
PostgreSQL. The rebuild protocol reads Kafka end offsets *before* scanning, then consumes from them, so
no update in flight is lost. Measured: 5.3 s for 131K reviews; restore 825 ms. `GET /api/admin/index-verify`
diffs the index against PostgreSQL.

**Consistency trade-offs?**
PostgreSQL is strongly consistent; search is eventually consistent (p50 119 ms, p95 266 ms
write→searchable here). Results are hydrated from PostgreSQL, so a review deleted there is never shown
even if the index lags. Ranking may use a slightly older version (each hit reports `version` and
`indexedVersion`). Keyset cursors don't duplicate or skip on a stable index but can shift if it changes
between pages.

**How would this scale beyond one machine?**

* Shard by document: hash(review id) → N index shards. Each consumes the Kafka partitions it owns (key =
  review id, so a review always lands on one shard). This is the reason the topic is keyed by review id.
* Query fan-out: scatter to all shards, merge top-K. Global BM25 statistics need either a df exchange
  phase or approximate per-shard idf. With hash sharding, per-shard statistics converge.
* Replicas: run R consumers per shard (separate groups) for read throughput and failover.
* Checkpoints go to object storage so a new replica bootstraps from a snapshot instead of the database.

**Shard by term or by document?**
Document sharding: writes touch one shard, shards are balanced, and adding a document never needs a
cross-shard transaction. The cost is fan-out on every query. Term sharding makes a single-term query
hit one node, but multi-term queries need cross-shard intersection and hot terms create hot shards.

**What did the benchmarks reveal?**

1. Redis was slower than the in-process index (2.2 ms vs 0.5 ms for that stage), so it ships disabled.
   "Add a cache" is not automatically a win.
2. Checkpoints block the single writer for about 1 s, which explains the p99 freshness spikes. The fix
   is background serialization from an immutable view.
3. Write throughput is bound by PostgreSQL commits (~460/s with 8 writers), not by the indexer.
4. Large OR queries are the slowest index category (2.8 ms) because every match is scored; WAND/MaxScore
   would prune.

**Why not just use Elasticsearch/OpenSearch in production?**
You usually *should*: it gives sharding, replication, analyzers (stemming, synonyms, CJK), phrase and
fuzzy queries, aggregations, highlighting, snapshot/restore and years of operational hardening. I built
the index here to show I understand what such an engine does inside: postings, intersection,
BM25, compaction, persistence, and consistency with a source of truth. The pipeline around it (outbox,
Kafka, versioned idempotent consumers, rebuild-from-source) is exactly what you'd keep in front of
Elasticsearch. A custom index is justified only for narrow needs: embedded/in-process search with
microsecond latency, unusual scoring, or tight memory control.
