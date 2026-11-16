# Benchmark methodology

Every number in `docs/benchmark-results.md` comes from a file in `benchmarks/results/` produced by the
commands below. Benchmarks never run as part of `mvnw verify`.

## Machine (all runs)

| | |
|---|---|
| CPU | Intel i7-11370H, 4 cores / 8 threads (laptop, shared with a desktop session and browsers) |
| RAM | 15.7 GB total; 1–3 GB free during runs |
| OS | Windows 11 Home 10.0.26200 |
| Containers | Docker Desktop 28.0.1, WSL2 VM with 8 GB / 8 CPUs; PostgreSQL 17.12 (768 MB limit, `shared_buffers=128MB`, `synchronous_commit=on`), Kafka 4.2.2 (384 MB heap), Redis 8.6.8 |
| JVM | Temurin 21.0.13, G1; app `-Xmx1g`, harness `-Xmx2g` |

A laptop with other software running is noisy: identical k6 runs differed by about 2× at p95. Medians
are stable; treat tails as indicative.

## 1. In-process index vs. PostgreSQL scan (`IndexBenchmark`)

```bash
cd backend
./mvnw -q test-compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt -Dmdep.includeScope=test
java -Xmx2g -cp "target/test-classes;target/classes;$(cat target/cp.txt)" \
     com.reviewpulse.bench.IndexBenchmark 10000,100000 ../benchmarks/results
```
(Use `:` instead of `;` as the classpath separator on Linux/macOS.)

* **Same rows for both sides:** `bench_reviews_<n>` = the first n dataset reviews ordered by
  hash-derived id (a deterministic pseudo-random sample). The index is built from that table.
* **Workload (seeded):** 8 categories × 40 queries derived from a 10% sample of the corpus.
  * common single terms: highest df
  * rare single terms: df 2 to N/5000
  * AND of 2 and of 3 terms taken from one real review (so ≥ 1 match)
  * OR of 3 mid-frequency terms (df N/1000 to N/20)
  * AND-2 with `rating ≥ 4 AND verified`
  * product filter + a term
  * OR of the three most common terms (largest result sets)
* **Index timing:** 3,000 warm-up queries, then 5 rounds of every query, top-20 by relevance and
  separately by recency. Wall-clock per call, in-process: no HTTP, no hydration.
* **Baseline:** for the first 8 queries of each category, `SELECT id … WHERE <predicates> ORDER BY
  reviewed_at DESC, id LIMIT 20` over JDBC. The run is executed 3 times and the first is discarded.
  * The primary predicate is `(title || ' ' || body) ~* '\mterm\M'`, a case-insensitive word-boundary
    regex: the closest SQL equivalent of token matching. AND is one predicate per term, OR is an
    alternation.
  * An `ILIKE '%term%'` variant (substring semantics) is reported for latency reference only.
  * The baseline is a sequential scan by design: no trigram or full-text index, because PostgreSQL
    full-text search is not the system under test.
* **Agreement:** `count(*)` of the regex query vs the index's `totalHits` for the same 8 queries.
* **Throughput:** 10 s closed-loop, 1 and 4 threads, all categories round-robin.
* **Memory:** heap after 3× `System.gc()` before and after the build (approximate), plus the index's
  own structural estimate.

Caveat: index timings exclude the ~0.3 ms JDBC round trip that the SQL timings include. The ratios below
are dominated by the scan (12–270 ms), so this does not change the conclusion. The end-to-end HTTP test
(section 2) includes hydration over JDBC.

## 2. End-to-end HTTP load (`benchmarks/k6/search-load.js`, k6 2.4.0)

```bash
REVIEWPULSE_RATE_LIMIT_ENABLED=false REVIEWPULSE_CACHE_ENABLED=<true|false> java -jar backend/target/*.jar
k6 run -e LABEL=search-100k-cache-on -e RATE=50 benchmarks/k6/search-load.js
```
Three open-loop (constant-arrival-rate) scenarios at 50 requests/s, run one after another:

* `mixed` (60 s): random 2-term queries, 60/40 AND/OR, random sorts, 30% rating filter, 20% verified filter.
* `popular` (30 s): 5 repeated queries.
* `cold` (30 s): unique queries.

The full API path is measured: validation, index (or cache), hydration of 20 rows from PostgreSQL,
highlighting, JSON. Each configuration was run after a restart; the first run warms the JVM and is
discarded, and the reported run is the next one.

## 3. Freshness (`benchmarks/freshness.py`)

200 `POST /api/reviews` at 5/s. Each one is polled via `GET /api/reviews/{id}` every 20 ms until
`searchable`. The client-observed delay includes the outbox poll interval (100 ms) and the polling
granularity. The server-side commit→indexed timer (`reviewpulse.index.delay`) is recorded too.

## 4. Indexing throughput (`benchmarks/replay_throughput.py`)

Replays real, not-yet-imported review slices through the full write path (PostgreSQL + outbox + Kafka +
indexer) at a target rate, with 1 or 8 simulator writer threads. The metrics endpoint is sampled every
second until the replay finishes and the backlog (outbox pending + Kafka lag) is zero. Each slice is used
once (replaying it again would only produce duplicates). Afterwards `GET /api/admin/index-verify`
confirms the index equals PostgreSQL.

## Not run

* **Full ~700K-review benchmark.** The procedure is identical with `IndexBenchmark 0` after
  `prepare_dataset.py import` without `--limit`. It was not run because the system drive had 2.4 GB free
  at that point, and a full import adds roughly 1 GB of PostgreSQL data and WAL inside Docker's virtual disk.
