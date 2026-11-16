"""ReviewPulse dataset CLI.

  python prepare_dataset.py download
  python prepare_dataset.py import --limit 10000 --batch-size 5000
  python prepare_dataset.py export-replay --offset 100000 --limit 5000 --name beauty-live-5k
  python prepare_dataset.py verify-file [--file PATH]

Only files inside the repository's data/ directory are accepted as inputs/outputs.
"""

from __future__ import annotations

import argparse
import json
import logging
import sys
from pathlib import Path

from rp_data.config import DATA_DIR, postgres_conninfo
from rp_data.download import EXPECTED_SHA256, download, is_valid, sha256_of
from rp_data.importer import run_import
from rp_data.replay import export_replay

DEFAULT_SOURCE = DATA_DIR / "raw" / "All_Beauty.parquet"
log = logging.getLogger("prepare_dataset")


def _safe_data_path(raw: str) -> Path:
    """Resolve a user-supplied path and refuse anything outside data/ (no path traversal)."""
    path = Path(raw).resolve()
    data_root = DATA_DIR.resolve()
    if data_root != path and data_root not in path.parents:
        raise SystemExit(f"refusing path outside {data_root}: {path}")
    if path.suffix not in (".parquet", ".jsonl"):
        raise SystemExit("source must be a .parquet or .jsonl file")
    return path


def _positive(value: str) -> int:
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return number


def _non_negative(value: str) -> int:
    number = int(value)
    if number < 0:
        raise argparse.ArgumentTypeError("must be >= 0")
    return number


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("download", help="download the pinned Parquet file (verified by SHA-256)")

    verify = sub.add_parser("verify-file", help="check a local source file against the pinned checksum")
    verify.add_argument("--file", default=str(DEFAULT_SOURCE))

    imp = sub.add_parser("import", help="bulk import into PostgreSQL (idempotent)")
    imp.add_argument("--file", default=str(DEFAULT_SOURCE))
    imp.add_argument("--limit", type=_positive, default=None, help="process only the first N rows")
    imp.add_argument("--batch-size", type=_positive, default=5_000)

    rep = sub.add_parser("export-replay", help="write a timestamp-sorted slice for the replay simulator")
    rep.add_argument("--file", default=str(DEFAULT_SOURCE))
    rep.add_argument("--offset", type=_non_negative, required=True)
    rep.add_argument("--limit", type=_positive, required=True)
    rep.add_argument("--name", required=True)
    return parser


def main(argv: list[str]) -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    args = build_parser().parse_args(argv)

    if args.command == "download":
        download(DEFAULT_SOURCE)
        return 0

    source = _safe_data_path(args.file)
    if not source.is_file():
        log.error("source file not found: %s (run `download` or place the file there)", source)
        return 2

    if args.command == "verify-file":
        ok = is_valid(source)
        log.info("%s: %s", source.name, "matches pinned checksum" if ok else "does NOT match pinned checksum")
        return 0 if ok else 1

    sha = sha256_of(source)
    if sha != EXPECTED_SHA256:
        log.warning("source checksum %s differs from the pinned revision; results are not comparable", sha)

    if args.command == "import":
        report = run_import(postgres_conninfo(), source, limit=args.limit, batch_size=args.batch_size,
                            sha256=sha, stats_dir=DATA_DIR / "import-stats", rejects_dir=DATA_DIR / "rejects")
    else:
        report = export_replay(source, DATA_DIR / "replay", name=args.name, offset=args.offset, limit=args.limit)
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
