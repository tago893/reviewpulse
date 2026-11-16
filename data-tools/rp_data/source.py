"""Bounded-memory streaming over the Parquet (or JSONL) source file."""

from __future__ import annotations

import json
from pathlib import Path
from typing import Iterator

import pyarrow.parquet as pq

# The images column is large and unused; never read it.
SOURCE_COLUMNS = [
    "rating", "title", "text", "asin", "parent_asin", "user_id",
    "timestamp", "helpful_vote", "verified_purchase",
]


def iter_raw_batches(path: Path, batch_size: int, limit: int | None, offset: int = 0) -> Iterator[list[dict]]:
    """Yield lists of raw dict records, at most `batch_size` per list.

    Only one Arrow record batch is held in memory at a time. `offset` skips leading rows,
    `limit` caps the number of rows yielded (after the offset).
    """
    if batch_size <= 0:
        raise ValueError("batch_size must be positive")
    if limit is not None and limit < 0:
        raise ValueError("limit must be >= 0")
    if path.suffix == ".jsonl":
        yield from _iter_jsonl(path, batch_size, limit, offset)
        return

    parquet = pq.ParquetFile(path)
    available = set(parquet.schema_arrow.names)
    missing = [c for c in SOURCE_COLUMNS if c not in available]
    if missing:
        raise ValueError(f"source file is missing required columns: {missing}")

    skipped = 0
    emitted = 0
    for batch in parquet.iter_batches(batch_size=batch_size, columns=SOURCE_COLUMNS):
        rows = batch.to_pylist()
        if skipped < offset:
            take_from = min(len(rows), offset - skipped)
            skipped += take_from
            rows = rows[take_from:]
            if not rows:
                continue
        if limit is not None:
            rows = rows[: max(0, limit - emitted)]
        if rows:
            emitted += len(rows)
            yield rows
        if limit is not None and emitted >= limit:
            return


def _iter_jsonl(path: Path, batch_size: int, limit: int | None, offset: int) -> Iterator[list[dict]]:
    batch: list[dict] = []
    emitted = 0
    with path.open("r", encoding="utf-8") as handle:
        for line_no, line in enumerate(handle):
            if line_no < offset:
                continue
            if limit is not None and emitted >= limit:
                break
            line = line.strip()
            if not line:
                continue
            try:
                record = json.loads(line)
            except json.JSONDecodeError:
                record = {"__malformed__": True}
            batch.append(record if isinstance(record, dict) else {"__malformed__": True})
            emitted += 1
            if len(batch) >= batch_size:
                yield batch
                batch = []
    if batch:
        yield batch
