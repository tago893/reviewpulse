import json
import os

import psycopg
import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from rp_data.config import postgres_conninfo
from rp_data.importer import ImportStats, run_import, validate_batch
from rp_data.replay import export_replay
from rp_data.source import iter_raw_batches


def make_rows(n, start_ts=1588687728000):
    return [{
        "rating": float(1 + i % 5), "title": f"title {i}", "text": f"body text {i}",
        "asin": "B00YQ6X8EO", "parent_asin": "B00YQ6X8EO", "user_id": f"U{i}",
        "timestamp": start_ts - i * 1000, "helpful_vote": i, "verified_purchase": i % 2 == 0,
    } for i in range(n)]


@pytest.fixture
def parquet_file(tmp_path):
    rows = make_rows(25)
    rows[3]["rating"] = 9.0          # rejected
    rows[7] = dict(rows[6])          # exact duplicate of row 6
    path = tmp_path / "fixture.parquet"
    pq.write_table(pa.Table.from_pylist(rows), path, row_group_size=4)
    return path


def test_streaming_respects_batch_size_limit_and_offset(parquet_file):
    batches = list(iter_raw_batches(parquet_file, batch_size=3, limit=10))
    assert all(len(b) <= 3 for b in batches)
    assert sum(len(b) for b in batches) == 10
    first = next(iter_raw_batches(parquet_file, batch_size=5, limit=1, offset=5))
    assert first[0]["user_id"] == "U5"


def test_validate_batch_counts_rejects_and_in_batch_duplicates(parquet_file):
    stats, rejects = ImportStats(), []
    rows = [r for b in iter_raw_batches(parquet_file, batch_size=100, limit=None) for r in b]
    valid = validate_batch(rows, 0, stats, rejects)
    assert stats.processed == 25
    assert stats.rejected == 1 and rejects == [{"row": 3, "reason": "invalid_rating"}]
    assert stats.duplicates == 1
    assert len(valid) == 23


def test_malformed_jsonl_lines_are_rejected(tmp_path):
    path = tmp_path / "f.jsonl"
    path.write_text(json.dumps(make_rows(1)[0]) + "\n{not json\n[1,2]\n", encoding="utf-8")
    stats, rejects = ImportStats(), []
    rows = [r for b in iter_raw_batches(path, batch_size=10, limit=None) for r in b]
    assert len(validate_batch(rows, 0, stats, rejects)) == 1
    assert stats.reject_reasons["malformed_record"] == 2


def test_replay_export_is_sorted_by_timestamp(parquet_file, tmp_path):
    report = export_replay(parquet_file, tmp_path, name="t1", offset=0, limit=25)
    lines = [json.loads(line) for line in (tmp_path / "t1.jsonl").read_text(encoding="utf-8").splitlines()]
    assert report["records"] == len(lines) == 23
    assert [x["reviewedAt"] for x in lines] == sorted(x["reviewedAt"] for x in lines)
    with pytest.raises(ValueError):
        export_replay(parquet_file, tmp_path, name="../evil", offset=0, limit=5)


def _db_available() -> bool:
    if os.environ.get("RP_SKIP_DB_TESTS"):
        return False
    try:
        with psycopg.connect(postgres_conninfo()) as conn:
            return conn.execute("SELECT to_regclass('public.reviews')").fetchone()[0] is not None
    except psycopg.Error:
        return False


@pytest.mark.skipif(not _db_available(), reason="local PostgreSQL with migrated schema not available")
def test_import_is_idempotent_against_real_postgres(tmp_path):
    rows = make_rows(12, start_ts=1288687728000)
    for r in rows:
        r["user_id"] = "pytest-" + r["user_id"]
    path = tmp_path / "idem.parquet"
    pq.write_table(pa.Table.from_pylist(rows), path)
    conninfo = postgres_conninfo()
    try:
        first = run_import(conninfo, path, limit=None, batch_size=5, sha256=None,
                           stats_dir=tmp_path / "s", rejects_dir=tmp_path / "r")
        second = run_import(conninfo, path, limit=None, batch_size=5, sha256=None,
                            stats_dir=tmp_path / "s", rejects_dir=tmp_path / "r")
        assert first["accepted"] == 12 and first["duplicates"] == 0
        assert second["accepted"] == 0 and second["duplicates"] == 12
    finally:
        with psycopg.connect(conninfo, autocommit=True) as conn:
            run_ids = [first_id for first_id in (locals().get("first", {}).get("import_run_id"),
                                                 locals().get("second", {}).get("import_run_id")) if first_id]
            conn.execute("DELETE FROM reviews WHERE import_run_id = ANY(%s)", (run_ids,))
            conn.execute("DELETE FROM import_runs WHERE id = ANY(%s)", (run_ids,))
