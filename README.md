# ReviewPulse

Real-time product-review search over **real Amazon Reviews 2023 data** (All_Beauty, 701K reviews), built
around a **custom inverted index written from scratch in Java**: no Lucene, Elasticsearch, Solr or
PostgreSQL full-text search. Writes go to PostgreSQL through a **transactional outbox**, flow through
**Kafka**, and are applied by a single-writer indexer with **version-checked, idempotent** updates,
**checkpointed persistence** and **crash recovery**.

![Search UI](docs/screenshots/search.png)

**Demo video (27 s):** [docs/demo/reviewpulse-demo.webm](docs/demo/reviewpulse-demo.webm). It is
recorded by a Playwright walkthrough: search with highlighting and filters, open a review, create one and
watch it become searchable, vote, delete, and check system status.

## Highlights (measured; see [benchmark results](docs/benchmark-results.md))

* **Full dataset, 694K reviews:** index query p50 0.74 ms. That is **46× to ~30,000× faster** than a
  naive PostgreSQL word-boundary scan of the same rows (82 ms to 1.9 s at p50), with match counts within
  0.23%. At 100K reviews the index p50 is 0.07 ms.
* **End-to-end `GET /api/search` at 50 req/s per scenario:** p95 7–14 ms on the full dataset (5–9 ms at
  100K), including hydration from PostgreSQL. 0 errors.
* **Write → searchable:** p50 about 120 ms through outbox → Kafka → index. p95 is 266 ms at 100K and
  1.7 s at 694K (checkpoint pauses, see limitations). 200/200 within 10 s at both sizes.
* **Replayed live traffic:** ~420 events/s end to end with 8 writers. Duplicate and out-of-order events
  were injected, and the index still matched PostgreSQL exactly afterwards (131,264 = 131,264).
* **Recovery:** restart from a 19.6 MB checkpoint in 825 ms, vs 5.3 s for a full rebuild.
* **Measured, then disabled by default:** the Redis cache was slower than the in-process index at this
  scale.

## Architecture

```mermaid
flowchart LR
    UI[React UI] --> API[Spring Boot API]
    API -->|1 tx: review + outbox row| PG[(PostgreSQL<br/>source of truth)]
    API -->|read lock| IDX[Custom inverted index<br/>BM25F, in memory]
    API -->|hydrate hits| PG
    PUB[Outbox publisher] --> PG
    PUB -->|acks=all, idempotent| K[[Kafka]]
    K --> W[Indexer<br/>single writer]
    W -->|version-checked batches| IDX
    W -->|checkpoint + offsets| SNAP[(snapshot file)]
    W -->|poison| DLT[[dead-letter topic]]
    DT[data-tools] -->|bulk COPY| PG
```

More: [architecture](docs/architecture.md) · [search index internals](docs/search-index.md) ·
[consistency guarantees](docs/consistency.md) · [security & trust boundary](docs/security.md) ·
[dataset](docs/dataset.md) · [benchmark methodology](docs/benchmark-methodology.md) ·
[interview notes](docs/interview-prep.md) · [decisions](docs/decisions/)

## What's implemented

| Area | Implementation |
|---|---|
| Tokenizer | Unicode letters/digits, NFKD diacritic folding, apostrophe joining, negation-preserving stop words, offsets for highlighting |
| Index | docId-sorted append-only posting lists (packed title/body tf), column-store doc table, exact df/length stats, lazy deletion + compaction |
| Queries | AND via leapfrog intersection with galloping search; OR via heap k-way merge; product/rating/verified filters; top-K heap; keyset cursors; time budget |
| Ranking | BM25F (k1 1.2, b 0.75, title ×2), separate relevance/recency/helpfulness total orders |
| Writes | optimistic versions (`409` on conflict), outbox row in the same transaction |
| Events | fat events (full state per version) → duplicates and late older versions are no-ops; DLT for poison |
| Persistence | CRC-checked binary checkpoint + Kafka offsets, atomic rename; validated on restart; rebuild fallback |
| Ops | health UP/DEGRADED/DOWN per component, metrics summary + Prometheus, rate limits, body limits, admin token |
| Demo | React search/status/demo UI; replay simulator with rate control and duplicate/out-of-order injection |

## Run it locally

Requirements: Docker Desktop, JDK 21, Node ≥ 20.19 (UI only), Python 3.10+ (data tools).
Everything binds to `127.0.0.1`.

```bash
cp .env.example .env                      # set REVIEWPULSE_ADMIN_TOKEN
docker compose up -d                      # PostgreSQL :15432, Kafka :9092, Redis :6379

# 1. Backend (creates the schema, builds the index, consumes Kafka)
cd backend && ./mvnw spring-boot:run      # http://127.0.0.1:8080, Swagger UI at /api/docs/ui

# 2. Real data (~139 MB download, verified by SHA-256; kept out of Git)
cd data-tools
python -m venv .venv && .venv/Scripts/pip install -r requirements.txt   # .venv/bin/pip on Linux/macOS
.venv/Scripts/python prepare_dataset.py download
.venv/Scripts/python prepare_dataset.py import --limit 100000           # idempotent; index rebuilds automatically
.venv/Scripts/python prepare_dataset.py export-replay --offset 100000 --limit 5000 --name beauty-live-5k

# 3. UI
cd frontend && npm install && npm run dev # http://127.0.0.1:5173
```

Or run the whole stack in containers (backend + nginx-served UI, still bound to `127.0.0.1`):

```bash
docker compose --profile app up -d --build   # UI http://127.0.0.1:3000, API http://127.0.0.1:8080
```

Try it:

```bash
curl "http://127.0.0.1:8080/api/search?q=not+greasy+moisturizer&ratingMin=4&sort=helpfulness"
curl -X POST http://127.0.0.1:8080/api/reviews -H 'Content-Type: application/json' \
     -d '{"parentAsin":"B0DEMO0001","title":"Zesty test","body":"brand new words","rating":5}'
curl http://127.0.0.1:8080/api/health
```

### API

| Method | Path | Notes |
|---|---|---|
| GET | `/api/search` | `q`, `operator` (AND/OR), `ratingMin/Max`, `parentAsin`, `verifiedPurchase`, `sort` (relevance/recency/helpfulness), `pageSize` ≤ 50, `cursor` |
| GET | `/api/reviews/{id}` | includes `version`, `indexedVersion`, `searchable` |
| POST / PUT / DELETE | `/api/reviews[/{id}]` | PUT/DELETE require `expectedVersion`; 2xx = committed, indexing is async |
| POST | `/api/reviews/{id}/helpful` | metadata-only index update |
| GET | `/api/health`, `/api/metrics/summary` | also `/actuator/prometheus` |
| POST/GET | `/api/admin/reindex`, `/snapshot`, `/index-status`, `/index-verify`, `/replay/*` | `X-Admin-Token` required |

Review ids are strings in JSON: dataset ids exceed 2^53 and would lose precision as JavaScript numbers.

## Tests

```bash
cd backend && ./mvnw verify      # 130 unit tests + 28 integration tests (Testcontainers: PostgreSQL, Kafka, Redis)
                                 # merged coverage report: backend/target/site/jacoco-merged/index.html
cd data-tools && .venv/Scripts/python -m pytest -q    # 26 tests (one uses the local PostgreSQL)
cd frontend && npm run build     # strict TypeScript + production build
cd frontend && npm run e2e       # Playwright end-to-end tests against the running stack (uses installed Edge)
cd frontend && npm run demo      # records the walkthrough video to frontend/test-results/
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) runs on every push: the backend unit +
Testcontainers integration tests (real PostgreSQL, Kafka and Redis on the runner), the data-tool tests,
and the frontend typecheck/build. The end-to-end tests need a running stack with data, so they run
locally rather than in CI.

Coverage (unit + integration, JaCoCo, last run): **92% of instructions, 81% of branches**. The search
engine packages are at 97–100% instructions; the weakest branch coverage is in the indexer worker's
Kafka-failure handling (events package, 63%), which the outage ITs exercise only partly.

Highlights of what is tested:

* Search results are compared against an **independent brute-force implementation** over randomized
  corpora with inserts, updates, deletes, duplicates and stale events, before and after compaction.
* Testcontainers ITs cover create → searchable, old terms disappearing after an update, deletes,
  duplicate and out-of-order Kafka delivery, a poison message reaching the DLT, rollback publishing
  nothing, concurrent updates (exactly one winner) and concurrent votes.
* Rebuild during writes, crash after checkpoint (older snapshot + lost memory), a corrupt checkpoint,
  failing checkpoints never advancing Kafka offsets, Kafka/PostgreSQL/Redis outages (`docker pause`),
  and cache invalidation.

## Limitations (honest list)

* Single node: one in-memory index per process; no sharding or replication (design notes in
  [interview-prep](docs/interview-prep.md)).
* Search is eventually consistent; no read-your-writes in search; cursors can shift if the index changes
  between pages ([consistency](docs/consistency.md)).
* Checkpoints pause indexing (about 1 s at 100K reviews, about 4 s at 694K), which causes the freshness
  tail; there is no background/incremental checkpointing yet.
* Huge OR queries score every match (41 ms at 694K for a 357K-match query); no WAND/MaxScore pruning.
* No stemming, no phrase queries, no CJK segmentation; BM25 parameters are defaults, not tuned.
* End-to-end browser tests run locally against a stack with data, not in CI; there are no frontend unit
  tests.
* Admin auth is a shared token for local demos, not user authentication.
* Benchmarks come from one busy laptop; tails vary between runs.

## Dataset & attribution

Amazon Reviews 2023 by McAuley Lab (Hou et al., *Bridging Language and Items for Retrieval and
Recommendation*, arXiv:2403.03952). The dataset card declares no license; this project uses the data for
non-commercial research and education, never commits or redistributes it, and does not store reviewer
ids. See [docs/dataset.md](docs/dataset.md).
