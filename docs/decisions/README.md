# Architecture decision records

Short records of decisions that shaped the code. Status: accepted unless noted.

## 0001: Build the inverted index in-process, from scratch
The brief requires our own retrieval code. The index lives in the API process: queries cost
microseconds and need no network hop, at the price of single-node scale and memory proportional to the
corpus (83 MB measured at 100K reviews). See `docs/search-index.md`.

## 0002: Deterministic review identifiers
Dataset ids are derived from SHA-256(category|user|asin|timestamp), put in [2^62, 2^63). API ids come
from a sequence below 2^62, so the ranges are disjoint. Re-imports and replays are idempotent without a
lookup table. Ids are strings in JSON (> 2^53). Hash collisions are rejected and reported, never merged.

## 0003: Fat, versioned events and state-based consumers
Each event carries the complete review state at a version. The consumer applies "newer version wins",
which makes duplicates and reordering harmless and means a dropped intermediate version loses nothing.
Cost: larger messages (bounded by the 32,000-character body limit).

## 0004: Transactional outbox instead of dual writes
The review row and the event row commit atomically; a relay publishes with acks=all and marks rows
afterwards. Accepts at-least-once delivery plus consumer deduplication instead of distributed transactions.

## 0005: Plain kafka-clients with manual assignment
A single indexer owns all partitions with `assign()`. Offsets are stored in the checkpoint (the recovery
point) and committed to Kafka only for observability, after the checkpoint is durable. There are no
consumer-group rebalances to reason about. Revisit when the index is sharded.

## 0006: Checkpoint = compacted snapshot + offsets, written atomically
Simplest persistence that gives fast restarts with no lost events. Known cost: about 1 s pause per
checkpoint at 100K+ reviews (measured). Next step: background serialization from copy-on-write segments.

## 0007: Bulk bootstrap bypasses Kafka
700K historical rows go in with `COPY` + `ON CONFLICT DO NOTHING`; the indexer notices the new
`import_runs` row and rebuilds. Kafka carries only live changes.

## 0008: Redis cache off by default (decided by measurement)
At 100K reviews a Redis round trip (~2 ms through Docker Desktop) costs more than ranking in process
(~0.5 ms), and end-to-end tails showed no consistent gain. The cache stays implemented and tested. Its key
includes the index generation, so it can never serve results across an index change.

## 0009: Spring Boot 4.1 / Java 21 / Testcontainers 2
Latest GA versions at build time (verified on Maven Central, 2026-11-15). JDK 25 was present on the
machine but its install was incomplete, so Temurin 21.0.13 was installed.
