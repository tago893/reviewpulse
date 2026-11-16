# Consistency guarantees

PostgreSQL is the source of truth. The inverted index is a **rebuildable projection** fed by Kafka.
Search is eventually consistent; what follows is exactly what is (and is not) guaranteed.

## The write path

```
client ──POST/PUT/DELETE──▶ ReviewService
                              │  ONE transaction:
                              │   UPDATE reviews ... WHERE id=? AND version=?  RETURNING *
                              │   INSERT INTO outbox_events (payload = full row at that version)
                              ▼
                         COMMIT  ──────────────▶ HTTP 2xx  (meta.indexing = PENDING)
                              │
                OutboxPublisher (every 100 ms, SKIP LOCKED batch)
                              │  send all, wait for acks=all, then mark PUBLISHED, commit
                              ▼
                 Kafka topic review-events (key = review id → per-review order)
                              │
                 IndexerWorker (single writer thread)
                              │  decode → version check → apply batch atomically
                              ▼
                    in-memory index  ──checkpoint──▶ index.snapshot (+ offsets)  ──▶ commit offsets
```

## Guarantees

| # | Guarantee | Mechanism | Tested by |
|---|---|---|---|
| G1 | A rolled-back write never produces an event | outbox row in the same transaction | `rolledBackWriteNeverPublishesAnEvent` |
| G2 | Every committed write eventually produces an event | outbox row is durable; publisher retries forever with capped backoff; only non-retriable errors after 5 attempts park a row as FAILED (visible in health) | `kafkaOutage…AndRecovers` |
| G3 | Lost updates are impossible | `UPDATE … WHERE version = :expected` → 409 | `concurrentUpdates…ExactlyOneWinner` |
| G4 | Duplicate delivery is harmless | apply only if `event.version > indexedVersion` | `duplicateAndOutOfOrder…`, `ReplayIT` |
| G5 | A late older event cannot overwrite newer state | same version check; tombstones keep versions of deleted reviews | `InvertedIndexTest`, `duplicateAndOutOfOrder…` |
| G6 | Skipping an intermediate version loses nothing | fat events carry the complete state | reasoning + randomized reference test |
| G7 | A deleted review is never shown, even if the index or cache lags | hits are hydrated from PostgreSQL; deleted rows dropped and counted | `reviewDeletedInDatabase…`, `CacheIT` |
| G8 | A crash loses no event | offsets committed only after a durable checkpoint; restart replays from the checkpoint | `crashAfterCheckpoint…` |
| G9 | Rebuild during live writes loses no update | end offsets read **before** the PostgreSQL scan; consume from them afterwards | `rebuildDuringConcurrentWrites…` |
| G10 | Queries never see half a batch | batch applied under the write lock; queries hold the read lock | `SearchIndexConcurrencyTest` |
| G11 | "Healthy" is never reported while results are known to be incomplete | index state + lag in every search response; health DEGRADED/DOWN | `kafkaOutage…`, `failingCheckpoint…` |

**Delivery semantics:** at-least-once from outbox to Kafka (a crash between broker ack and the
`PUBLISHED` update re-sends), at-least-once from Kafka to the index (replay after a crash), made
**effectively-once for index state** by idempotent, version-checked application. This is *not*
exactly-once processing, and Kafka transactions are not used. They would not cover the database/index
boundary anyway.

## Why the rebuild protocol is safe (G9)

1. Read end offsets **E** of every partition.
2. Scan PostgreSQL (one statement, so one MVCC snapshot) into a new index.
3. Swap it in; consume from **E**.

An event below E was published after its transaction committed, and that commit happened before E was
read, so the scan (which starts after) includes it. Changes committed after the scan's snapshot produce
events at or after E, which are replayed. Anything the scan already contained is dropped by the version
check. If Kafka is unreachable, the index is still built (search keeps working, state DEGRADED) and
consumption later starts from the beginning of the topic, which is correct, just slower.

## Checkpoints and restart (G8)

A checkpoint is the compacted index plus the offsets it reflects, written to a temp file, fsynced, and
atomically renamed. On startup it is used only if all of these hold:

* the tokenizer fingerprint is unchanged,
* the Kafka topic id is unchanged (the topic was not recreated),
* the partition set is unchanged,
* no bulk import has completed since the checkpoint,
* every stored offset lies within the topic's retained range.

Otherwise the index is rebuilt from PostgreSQL and the reason is reported (`checkpoint.lastRestore`).
`auto.offset.reset=none` makes retention gaps loud instead of silently skipping events.

**Limitation:** the directory is not fsynced after the rename (Windows does not support it). A power loss
right after a checkpoint may leave the previous checkpoint in place. Recovery then replays more events,
which is safe.

## What is NOT guaranteed

* **Read-your-writes in search.** A write returns before it is searchable (measured client-observed
  p50 119 ms, p95 266 ms, p99 860 ms at 100K reviews; see `docs/benchmark-results.md`). Clients that need it poll `GET /api/reviews/{id}` for
  `searchable: true`.
* **Stable pagination across index changes.** Cursors are keyset positions `(sort key, review id)`. If
  the index changes between pages, results move: a review whose score/helpfulness/timestamp changed can
  appear twice or be skipped, and BM25 scores shift slightly when corpus statistics change. On an
  unchanged index, pages never overlap or skip (tested against a brute-force reference).
* **Freshness of displayed vs. ranked state.** Fields shown come from PostgreSQL (fresh); the ranking
  used the indexed version. Each hit carries both `version` and `indexedVersion`.
* **Bulk imports are not streamed.** They bypass Kafka by design; the indexer notices the new
  `import_runs` row (every 15 s) and rebuilds.
* **Multi-instance deployment.** One indexer per index; several app instances would each need their own
  index and checkpoint directory (see `docs/interview-prep.md` for how it would scale).
