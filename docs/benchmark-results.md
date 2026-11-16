# Benchmark results

Measured 2026-11-15 on the machine described in `docs/benchmark-methodology.md`. Raw data:
`benchmarks/results/*.json`. Numbers are from single runs on a busy laptop, so medians are reliable and
tails are indicative.

## Summary

| Goal (from the brief) | Measured | Status |
|---|---|---|
| Search p95 < 250 ms at 100K reviews | 5.3–9.0 ms end-to-end p95 at 50 req/s/scenario, cache on; 8.7–33 ms cache off (run-to-run noise) | met |
| New reviews searchable within ~10 s | client-observed p50 119 ms, p95 266 ms, p99 860 ms; 200/200 under 10 s | met |
| No correctness failures after duplicate processing | replay with injected duplicates/out-of-order: 0 failures; index = PostgreSQL afterwards (131,264 = 131,264) | met |
| No missing documents after a rebuild | `index-verify` after rebuilds/restores: 0 missing, 0 extra, 0 behind | met |
| Full ~700K-review benchmark | not run: 2.4 GB free disk (see methodology) | not done |

## 1. Index vs. naive PostgreSQL scan (same rows, same queries, top-20)

**99,678 reviews** (`index-benchmark-100000.json`). Latency in ms.

| Query category | Index p50 | Index p95 | Regex scan p50 | Regex scan p95 | p50 ratio | Mean hits |
|---|---|---|---|---|---|---|
| common 1 term | 0.356 | 0.793 | 127.5 | 245.7 | ~360× | 11,871 |
| rare 1 term | 0.002 | 0.005 | 141.9 | 217.6 | >10,000× | 9 |
| AND 2 terms | 0.100 | 1.027 | 254.3 | 402.8 | ~2,500× | 847 |
| AND 3 terms | 0.057 | 0.558 | 246.8 | 327.8 | ~4,300× | 54 |
| OR 3 terms | 0.079 | 0.246 | 164.4 | 440.2 | ~2,100× | 1,554 |
| AND 2 + rating≥4 + verified | 0.051 | 0.577 | 68.5 | 104.9 | ~1,300× | 492 |
| product + term (selective) | 0.003 | 0.013 | 12.2 | 13.5 | ~4,000× | 2 |
| OR of top-3 common terms | 2.816 | 3.308 | 267.0 | 412.4 | ~95× | 53,846 |

* All 1,600 index queries: **p50 0.068 ms, p95 2.85 ms, p99 3.21 ms**. Sorting by recency instead of
  relevance is in the same range (`indexRecencyTop20`).
* The `ILIKE '%term%'` scan was in the same range as the regex scan (12–701 ms p50).
* **Where the index wins least:** huge OR queries (95×). They touch ~54K postings per query, and scoring
  every match dominates. The product-filtered SQL query is fast only because `parent_asin` has a B-tree
  index.
* **Scaling 10K → 100K (10×):** index common-term p50 0.178 → 0.356 ms; large OR 0.268 → 2.82 ms
  (proportional to postings touched); regex scan 24 → 128 ms (proportional to rows).
* **Agreement with the regex scan:** match counts differ by at most 0.76% of hits per category (e.g. the
  large OR: about 1 document in 53,846). The differences come from deliberate tokenizer rules the regex
  does not have: apostrophe joining (`isn’t` → `isnt`) and Unicode folding (`café` = `cafe`).

**10,000 reviews** (`index-benchmark-10000.json`): index p50 0.012 ms / p95 0.283 ms over all queries;
regex scan 2.1–57.7 ms p50.

### Build, memory, persistence, throughput (in-process)

| | 10K | 100K |
|---|---|---|
| Build from PostgreSQL | 1,193 ms (cold JIT) | 1,869 ms (53K docs/s) |
| Terms / postings | 13,511 / 227,960 | 39,216 / 2,272,412 |
| Heap retained (measured / structural estimate) | 10.1 / 7.6 MB | 83.1 / 62.3 MB |
| Checkpoint size / write / load | 1.8 MB / 156 / 198 ms | 16.4 MB / 1,026 / 403 ms |
| Mixed-workload throughput, 1 thread | 16,817 q/s | 1,514 q/s |
| Mixed-workload throughput, 4 threads | 36,168 q/s | 3,247 q/s |

Throughput at 100K is dominated by the large-OR category (~2.8 ms each), and scales about 2.1× from 1 to
4 threads (read lock, no contention with writers in this test).

## 2. End-to-end HTTP search (k6, 99,688 reviews, 50 req/s per scenario)

Full API path: validation, ranking, hydration of 20 rows from PostgreSQL, highlighting. 0 failed requests
in every run (~6,000 requests per run).

| Scenario | Cache ON p50 / p95 / p99 (ms) | Cache OFF p50 / p95 / p99 (ms) |
|---|---|---|
| mixed (random 2-term, filters, sorts) | 4.4 / 7.1 / 13.6 | 5.0 / 33.2 / 71.0 (earlier run: 5.1 / 15.0 / 56.3) |
| popular (5 repeated queries) | 3.3 / 5.3 / 10.6 | 3.1 / 12.3 / 30.9 |
| cold (unique queries) | 4.7 / 9.0 / 20.8 | 3.6 / 18.9 / 72.7 |

Server-side timers for the same runs: the index-or-cache stage was p50 2.2 ms with Redis vs 0.52 ms
without. A Redis round trip through Docker Desktop costs more than ranking 100K reviews in process.
Cache hit rate was 29% (the mixed scenario rarely repeats queries).

**Decision: the Redis cache ships disabled by default.** Medians are within about 1 ms either way, and the
tail differences are smaller than the run-to-run variance seen with identical settings (cache-off mixed
p95: 15.0 vs 33.2 ms). It stays available (`REVIEWPULSE_CACHE_ENABLED=true`) and fully tested. It would
pay off if ranking became expensive: much larger corpora, huge OR queries, or an index served from
another process.

## 3. Freshness (write → searchable), 99,688 reviews

200 reviews created through the API at 5/s (`freshness-100k.json`):

| | p50 | p95 | p99 | max |
|---|---|---|---|---|
| Client-observed (POST → `searchable: true`) | 119 ms | 266 ms | 860 ms | 1,258 ms |
| Server commit → indexed | 84 ms | 226 ms | 772 ms | |

The floor is the outbox poll interval (100 ms). The p99 outliers line up with periodic checkpoints (below).

## 4. Indexing throughput under replayed live traffic

Real, not-yet-imported reviews through PostgreSQL → outbox → Kafka → index (`replay-*.json`):

| Target | Writers | Achieved write rate (peak) | End-to-end avg | Max backlog | Commit→indexed p50 / p95 / p99 |
|---|---|---|---|---|---|
| 100/s | 1 | 100/s | 84.5 ev/s | 33 events | 105 / 193 / 218 ms |
| 500/s | 1 | 125/s | 114 ev/s | 75 events | 101 / 201 / 352 ms |
| 1,000/s | 1 | 139/s | 127 ev/s | 95 events | 96 / 185 / 235 ms |
| 1,000/s | 8 | 463/s | 422 ev/s | 275 events | 122 / 260 / 805 ms |

* With one writer, the ceiling (~130/s) is the simulator itself: one PostgreSQL transaction with a
  synchronous commit at a time (~7–8 ms each in Docker Desktop's VM).
* With 8 writers the pipeline sustained ~420 events/s end to end, with the backlog never above 275 events
  and draining as soon as writes stopped. Even then the indexer was not the limit: the ~460 writes/s
  ceiling is PostgreSQL commit throughput on this machine.
* After all replays (~31K events including injected duplicates and out-of-order versions):
  `index-verify` reported 131,264 live reviews in both PostgreSQL and the index, 0 missing, 0 extra,
  0 behind. Every injected duplicate/stale event was counted as `STALE_IGNORED`.

## 5. Recovery

| | Measured |
|---|---|
| Restart with valid checkpoint (119,323 reviews, 19.6 MB) | checkpoint loaded in 825 ms; index live ~1.1 s after application start; later events replayed from Kafka |
| Full rebuild from PostgreSQL (131,264 reviews, warm JVM) | 5,346 ms |
| Full rebuild at first start (99,688 reviews, cold JVM) | 4,623 ms |
| Periodic checkpoint at 100–131K reviews | 0.66–1.39 s each (compaction + serialization + fsync) |

## Bottlenecks found (and what would fix them)

1. **Checkpoints pause the indexer for about 1 s.** They run on the single writer thread, so events queue
   up meanwhile. This produces the p99 delay spikes (772–860 ms). Fix: serialize from an immutable view on
   a background thread (copy-on-write segments) so indexing continues. Not implemented.
2. **Write throughput is bounded by PostgreSQL commits** (~460/s with 8 writers here). Fix: group commit
   or batching at the source; outside the search system's scope.
3. **Large OR queries** cost about 2.8 ms because every match is scored. Fix: WAND/MaxScore dynamic
   pruning using per-term upper bounds. Not implemented; not needed at this scale.
4. **Redis on the hot path** was slower than the in-process index (see section 2), so it is disabled.
