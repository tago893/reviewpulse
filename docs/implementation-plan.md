# ReviewPulse — Implementation Plan

Written 2026-11-15 after inspecting the machine. This is the working plan; decisions that turned out
differently are recorded in `docs/decisions/`.

## 1. Environment (measured, not assumed)

| Item | Found | Consequence |
|---|---|---|
| CPU | i7-11370H, 4 cores / 8 threads | Benchmarks report this machine only |
| RAM | 15.7 GB total, **~1 GB free** at start (browsers) | Small container limits; 100K-review default; full 700K run is optional |
| Disk | 18 GB free | Dataset + images + deps fit (~3 GB) |
| Java | JDK 17 only; `~/.jdks/openjdk-25` is an incomplete install | Installed Temurin **21.0.13+9** (SHA-256 verified) to `~/.jdks/temurin-21.0.13` |
| Build | No Maven/Gradle | Maven Wrapper 3.3.4 → Maven **3.9.16** (SHA-256 pinned) |
| Docker | Desktop 28.0.1, VM 8 GB / 8 CPUs | Postgres, Kafka, Redis via Compose; Testcontainers for ITs |
| Node | v19.3.0 (EOL, outside Vite's supported range) | Use Node **24 LTS** for the frontend |
| Python | 3.10.4 | pyarrow 26 needs 3.11 → pinned **pyarrow 21.0.0**, psycopg 3.3.9 in `data-tools/.venv` |

## 2. Versions (verified against Maven Central / Docker Hub / PyPI on 2026-11-15)

Spring Boot 4.1.2 (latest GA; 4.2 is RC) · Java release 21 · Testcontainers 2.0.5 ·
PostgreSQL `postgres:17.12-alpine` · Kafka `apache/kafka:4.2.2` (KRaft, single node) ·
Redis `redis:8.6.8-alpine` · k6 2.4.0 · Node 24 LTS · React + Vite.

## 3. Dataset

* Source: McAuley Lab Amazon Reviews 2023, `raw_review_All_Beauty`, pinned revision
  `269765acc4057f11566d9e16106d109409e4c7c8`, file `full-00000-of-00001.parquet`.
* Verified: 139,089,758 bytes, SHA-256 `b40ae565…11d7` (matches Hugging Face `X-Linked-ETag`).
* 701,528 rows, 702 row groups; ~29% of review bodies contain `<br />` markup.
* Downloaded once by `data-tools` into `data/raw/` (git-ignored). Never downloaded at app startup.
* Attribution / terms documented in `docs/dataset.md`.

## 4. Key architectural decisions

1. **PostgreSQL is authoritative.** Reviews carry a monotonically increasing `version`.
2. **Transactional outbox.** Every mutation writes the review row and an outbox row in one transaction.
3. **Fat events.** Each event carries the complete review state at its version, so the consumer is
   state-based: apply iff `event.version > indexedVersion`. Duplicates and late older events are no-ops,
   and a skipped intermediate version cannot lose content.
4. **Custom in-memory inverted index** with append-only, docId-sorted posting lists, a live-docs bitset
   (lazy deletion), exact corpus statistics, and compaction at snapshot time. Single writer thread,
   read/write lock for queries.
5. **Index persistence = checkpointed snapshots.** A snapshot stores the index *and* the Kafka offsets it
   reflects. Written to a temp file, fsynced, atomically renamed, CRC-checked on load. Kafka offsets are
   committed only after the snapshot is durable. Recovery = load snapshot, replay from its offsets.
   Fallback = rebuild from PostgreSQL.
6. **Historical bootstrap bypasses Kafka**: Python streams Parquet → `COPY` → `INSERT … ON CONFLICT DO
   NOTHING`, then the index is rebuilt from PostgreSQL. Live changes go through outbox → Kafka.
7. **Redis is optional**, caches index hit-lists keyed by query fingerprint + index generation; results
   are always hydrated from PostgreSQL so deleted reviews are never shown from cache.
8. Modular monolith: one Spring Boot app with background workers, no microservices.

## 5. Smallest runnable MVP (end of Phase 1)

`docker compose up postgres` → `data-tools import --limit 10000` → backend builds index from PostgreSQL at
startup → `GET /api/search?q=dry+skin` returns real matching reviews ranked by the custom index.

## 6. Test gates between phases

| Gate | Must pass before moving on |
|---|---|
| Phase 0 → 1 | App starts, Flyway migrates, `/api/health` reports DB status; context test green |
| Phase 1 → 2 | Tokenizer + index unit tests; search over 10K real imported reviews returns matches |
| Phase 2 → 3 | Posting-list ops and BM25 checked against an independent brute-force reference implementation; cursor pagination has no duplicates/gaps on a static index |
| Phase 3 → 4 | Testcontainers IT: create → searchable, update replaces terms, delete removes, duplicate + stale events ignored, rollback publishes nothing |
| Phase 4 → 5 | Snapshot round-trip, corrupt-snapshot rebuild, restart recovery, Redis-down fallback ITs |
| Phase 5 → 6 | Frontend builds and drives real backend endpoints |
| Phase 6 | Benchmarks executed; numbers in docs come only from `benchmarks/results/` |

## 7. Risks

* Memory pressure on the dev machine → small heaps, container limits, 100K default dataset.
* Spring Boot 4.x APIs (Jackson 3, modular starters, Testcontainers 2 packages) are newer than most
  examples online → verify by compiling early.
* Windows + Docker Desktop + Testcontainers networking → validated in Phase 0/3 before relying on it.
