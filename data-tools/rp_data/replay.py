"""Export a bounded slice of real reviews, sorted by original timestamp, for the live replay simulator.

The slice should come from rows that were NOT bulk-imported (use --offset past the import --limit),
otherwise the replay only produces duplicates. The backend reads these files from data/replay only.
"""

from __future__ import annotations

import json
import re
from pathlib import Path

from .importer import ImportStats, validate_batch
from .source import iter_raw_batches

MAX_REPLAY_RECORDS = 200_000
NAME_PATTERN = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")


def export_replay(source: Path, out_dir: Path, *, name: str, offset: int, limit: int,
                  batch_size: int = 5_000) -> dict:
    if not NAME_PATTERN.match(name):
        raise ValueError("name must match ^[a-z0-9][a-z0-9_-]{0,63}$")
    if limit <= 0 or limit > MAX_REPLAY_RECORDS:
        raise ValueError(f"limit must be in 1..{MAX_REPLAY_RECORDS} (the slice is sorted in memory)")
    stats = ImportStats()
    rejects: list[dict] = []
    records = []
    for batch in iter_raw_batches(source, batch_size, limit, offset=offset):
        records.extend(validate_batch(batch, offset + stats.processed, stats, rejects))

    records.sort(key=lambda r: (r.timestamp_ms, r.source_key))
    out_dir.mkdir(parents=True, exist_ok=True)
    target = out_dir / f"{name}.jsonl"
    with target.open("w", encoding="utf-8", newline="\n") as handle:
        for r in records:
            handle.write(json.dumps({
                "id": str(r.review_id), "sourceKey": r.source_key, "parentAsin": r.parent_asin,
                "asin": r.asin, "title": r.title, "body": r.body, "rating": r.rating,
                "helpfulVotes": r.helpful_votes, "verifiedPurchase": r.verified_purchase,
                "reviewedAt": r.reviewed_at_iso,
            }, ensure_ascii=False) + "\n")
    return {"file": str(target), "offset": offset, "records": len(records), **stats.as_dict()}
