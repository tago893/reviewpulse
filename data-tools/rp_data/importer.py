"""Historical bootstrap: stream source rows into PostgreSQL without going through Kafka.

Per batch, in one transaction:
  1. validate + normalize rows in Python (rejects are counted with a reason code)
  2. de-duplicate by source_key inside the batch
  3. COPY into a temp staging table
  4. INSERT INTO reviews ... ON CONFLICT DO NOTHING RETURNING id
  5. classify rows that were not inserted: existing source_key -> duplicate, else id collision

Re-running the same import inserts nothing new: every row is classified as a duplicate.
"""

from __future__ import annotations

import json
import logging
from collections import Counter
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

import psycopg

from . import DATASET_CATEGORY
from .normalize import CleanReview, Rejected, normalize_record
from .source import iter_raw_batches

log = logging.getLogger(__name__)

STAGING_DDL = """
CREATE TEMP TABLE IF NOT EXISTS staging_reviews (
    id BIGINT, source_key CHAR(64), parent_asin VARCHAR(10), asin VARCHAR(10),
    title TEXT, body TEXT, rating SMALLINT, helpful_votes INTEGER,
    verified_purchase BOOLEAN, reviewed_at TIMESTAMPTZ
) ON COMMIT DELETE ROWS
"""

INSERT_FROM_STAGING = """
INSERT INTO reviews (id, source, source_key, parent_asin, asin, title, body, rating,
                     helpful_votes, verified_purchase, reviewed_at, import_run_id)
SELECT id, 'DATASET', source_key, parent_asin, asin, title, body, rating,
       helpful_votes, verified_purchase, reviewed_at, %s
FROM staging_reviews
ORDER BY id
ON CONFLICT DO NOTHING
RETURNING id
"""

CLASSIFY_NOT_INSERTED = """
SELECT s.id, EXISTS (SELECT 1 FROM reviews r WHERE r.source_key = s.source_key) AS is_duplicate
FROM staging_reviews s
WHERE s.id <> ALL(%s)
"""


@dataclass
class ImportStats:
    processed: int = 0
    inserted: int = 0
    duplicates: int = 0
    rejected: int = 0
    reject_reasons: Counter = field(default_factory=Counter)
    timestamps_converted_from_seconds: int = 0

    def as_dict(self) -> dict:
        return {
            "processed": self.processed,
            "accepted": self.inserted,
            "duplicates": self.duplicates,
            "rejected": self.rejected,
            "reject_reasons": dict(sorted(self.reject_reasons.items())),
            "timestamps_converted_from_seconds": self.timestamps_converted_from_seconds,
        }


def validate_batch(rows: list[dict], start_index: int, stats: ImportStats,
                   rejects: list[dict]) -> list[CleanReview]:
    """Normalize a batch, dropping in-batch duplicates. Mutates only the passed accumulators."""
    seen: dict[str, CleanReview] = {}
    for offset, raw in enumerate(rows):
        stats.processed += 1
        try:
            if raw.get("__malformed__"):
                raise Rejected("malformed_record")
            clean = normalize_record(raw, DATASET_CATEGORY)
        except Rejected as rejection:
            stats.rejected += 1
            stats.reject_reasons[rejection.reason] += 1
            rejects.append({"row": start_index + offset, "reason": rejection.reason})
            continue
        if clean.source_key in seen:
            stats.duplicates += 1
            continue
        if clean.timestamp_was_seconds:
            stats.timestamps_converted_from_seconds += 1
        seen[clean.source_key] = clean
    return list(seen.values())


def _copy_to_staging(cur: psycopg.Cursor, reviews: list[CleanReview]) -> None:
    columns = ("id, source_key, parent_asin, asin, title, body, rating, helpful_votes, "
               "verified_purchase, reviewed_at")
    with cur.copy(f"COPY staging_reviews ({columns}) FROM STDIN") as copy:
        for r in reviews:
            copy.write_row((r.review_id, r.source_key, r.parent_asin, r.asin, r.title, r.body,
                            r.rating, r.helpful_votes, r.verified_purchase,
                            datetime.fromtimestamp(r.timestamp_ms / 1000, tz=timezone.utc)))


def _write_batch(conn: psycopg.Connection, run_id: int, reviews: list[CleanReview],
                 stats: ImportStats, rejects: list[dict]) -> None:
    if not reviews:
        return
    with conn.transaction(), conn.cursor() as cur:
        cur.execute(STAGING_DDL)
        _copy_to_staging(cur, reviews)
        inserted_ids = [row[0] for row in cur.execute(INSERT_FROM_STAGING, (run_id,)).fetchall()]
        stats.inserted += len(inserted_ids)
        for review_id, is_duplicate in cur.execute(CLASSIFY_NOT_INSERTED, (inserted_ids,)).fetchall():
            if is_duplicate:
                stats.duplicates += 1
            else:
                stats.rejected += 1
                stats.reject_reasons["id_collision"] += 1
                rejects.append({"review_id": str(review_id), "reason": "id_collision"})


def _start_run(conn: psycopg.Connection, source: Path, sha256: str | None,
               limit: int | None, batch_size: int) -> int:
    with conn.transaction():
        row = conn.execute(
            "INSERT INTO import_runs (status, source_file, source_sha256, row_limit, batch_size) "
            "VALUES ('RUNNING', %s, %s, %s, %s) RETURNING id",
            (source.name, sha256, limit, batch_size)).fetchone()
    return int(row[0])


def _update_run(conn: psycopg.Connection, run_id: int, stats: ImportStats, status: str) -> None:
    finished = "now()" if status != "RUNNING" else "NULL"
    with conn.transaction():
        conn.execute(
            f"UPDATE import_runs SET status = %s, finished_at = {finished}, processed = %s, inserted = %s, "
            "rejected = %s, duplicates = %s, reject_reasons = %s::jsonb WHERE id = %s",
            (status, stats.processed, stats.inserted, stats.rejected, stats.duplicates,
             json.dumps(dict(stats.reject_reasons)), run_id))


def ensure_schema(conn: psycopg.Connection) -> None:
    row = conn.execute("SELECT to_regclass('public.reviews'), to_regclass('public.import_runs')").fetchone()
    if row[0] is None or row[1] is None:
        raise RuntimeError("schema not found: start the backend once so Flyway creates the tables")


def run_import(conninfo: str, source: Path, *, limit: int | None, batch_size: int,
               sha256: str | None, stats_dir: Path, rejects_dir: Path) -> dict:
    stats = ImportStats()
    rejects: list[dict] = []
    started = datetime.now(timezone.utc)
    with psycopg.connect(conninfo, autocommit=True) as conn:
        ensure_schema(conn)
        run_id = _start_run(conn, source, sha256, limit, batch_size)
        log.info("import run %d started: file=%s limit=%s batch_size=%d", run_id, source.name, limit, batch_size)
        try:
            for batch in iter_raw_batches(source, batch_size, limit):
                valid = validate_batch(batch, stats.processed, stats, rejects)
                _write_batch(conn, run_id, valid, stats, rejects)
                _update_run(conn, run_id, stats, "RUNNING")
                log.info("run %d: processed=%d inserted=%d duplicates=%d rejected=%d", run_id,
                         stats.processed, stats.inserted, stats.duplicates, stats.rejected)
        except BaseException:
            _update_run(conn, run_id, stats, "FAILED")
            raise
        _update_run(conn, run_id, stats, "COMPLETED")

    elapsed = (datetime.now(timezone.utc) - started).total_seconds()
    report = {"import_run_id": run_id, "source_file": source.name, "source_sha256": sha256,
              "limit": limit, "batch_size": batch_size, "started_at": started.isoformat(),
              "elapsed_seconds": round(elapsed, 2), **stats.as_dict()}
    _write_outputs(report, rejects, run_id, stats_dir, rejects_dir)
    return report


def _write_outputs(report: dict, rejects: list[dict], run_id: int, stats_dir: Path, rejects_dir: Path) -> None:
    stats_dir.mkdir(parents=True, exist_ok=True)
    (stats_dir / f"import-run-{run_id}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if rejects:
        rejects_dir.mkdir(parents=True, exist_ok=True)
        with (rejects_dir / f"import-run-{run_id}.jsonl").open("w", encoding="utf-8") as handle:
            for item in rejects:
                handle.write(json.dumps(item) + "\n")
