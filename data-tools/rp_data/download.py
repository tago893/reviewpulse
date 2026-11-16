"""Download the pinned All_Beauty Parquet file once, verifying size and SHA-256."""

from __future__ import annotations

import hashlib
import shutil
import urllib.request
from pathlib import Path

DATASET_REVISION = "269765acc4057f11566d9e16106d109409e4c7c8"
DATASET_URL = (
    "https://huggingface.co/datasets/McAuley-Lab/Amazon-Reviews-2023/resolve/"
    f"{DATASET_REVISION}/raw_review_All_Beauty/full-00000-of-00001.parquet"
)
EXPECTED_SIZE = 139_089_758
EXPECTED_SHA256 = "b40ae565f1073e0b4960e2f424fe0c82f3d270b7c4d00bc9272ddd6a8b7411d7"
CHUNK = 1 << 20


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def is_valid(path: Path) -> bool:
    return path.is_file() and path.stat().st_size == EXPECTED_SIZE and sha256_of(path) == EXPECTED_SHA256


def download(target: Path) -> Path:
    """Download to `target` unless a verified copy already exists. Returns the path."""
    if is_valid(target):
        print(f"verified existing file: {target}")
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    partial = target.with_suffix(target.suffix + ".part")
    print(f"downloading {DATASET_URL}\n  -> {partial}")
    request = urllib.request.Request(DATASET_URL, headers={"User-Agent": "reviewpulse-data-tools"})
    with urllib.request.urlopen(request, timeout=60) as response, partial.open("wb") as out:
        shutil.copyfileobj(response, out, CHUNK)
    size = partial.stat().st_size
    if size != EXPECTED_SIZE:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"size mismatch: got {size} bytes, expected {EXPECTED_SIZE}")
    actual = sha256_of(partial)
    if actual != EXPECTED_SHA256:
        partial.unlink(missing_ok=True)
        raise RuntimeError(f"SHA-256 mismatch: got {actual}")
    partial.replace(target)
    print(f"verified {target} ({size} bytes, sha256 {actual})")
    return target
