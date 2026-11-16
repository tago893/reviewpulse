"""Deterministic review identifiers.

source_key = SHA-256 hex of "<category>|<user_id>|<asin>|<timestamp_ms>".
review id  = (top 64 bits of the digest >> 2) | 2**62, i.e. a value in [2**62, 2**63).

API-created reviews use a PostgreSQL sequence in [1, 2**62), so the two ranges can never collide.
The raw user_id is only used to derive the key; it is never stored or logged.
"""

import hashlib

DATASET_ID_FLAG = 1 << 62
_ID_MASK = (1 << 62) - 1


def source_key(category: str, user_id: str, asin: str, timestamp_ms: int) -> str:
    material = f"{category}|{user_id}|{asin}|{timestamp_ms}".encode("utf-8")
    return hashlib.sha256(material).hexdigest()


def review_id_from_source_key(key: str) -> int:
    if len(key) != 64:
        raise ValueError("source key must be a 64-character SHA-256 hex digest")
    top64 = int(key[:16], 16)
    return ((top64 >> 2) & _ID_MASK) | DATASET_ID_FLAG
