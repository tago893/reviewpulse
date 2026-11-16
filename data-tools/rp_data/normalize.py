"""Validation and normalization of raw Amazon Reviews 2023 records.

Every function here is pure so it can be unit-tested without a database or the real dataset.
A record either becomes a CleanReview or is rejected with a single, stable reason code.
"""

from __future__ import annotations

import html
import math
import re
import unicodedata
from dataclasses import dataclass
from datetime import datetime, timezone

from . import DATASET_CATEGORY
from .ids import review_id_from_source_key, source_key

MAX_TITLE_CHARS = 500
MAX_BODY_CHARS = 32_000
MAX_HELPFUL_VOTES = 10_000_000

# Timestamps outside this window are rejected. Values below 1e11 are treated as seconds.
MIN_TIMESTAMP_MS = int(datetime(1995, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
MAX_TIMESTAMP_MS = int(datetime(2030, 1, 1, tzinfo=timezone.utc).timestamp() * 1000)
SECONDS_THRESHOLD = 100_000_000_000

ASIN_PATTERN = re.compile(r"^[A-Z0-9]{10}$")
_BR_TAG = re.compile(r"<br\s*/?>", re.IGNORECASE)
# Only real tags (a letter right after '<'), so text like "I <3 this" or "size <5" survives.
_HTML_TAG = re.compile(r"</?[A-Za-z][A-Za-z0-9]*(?:\s[^<>]{0,200})?/?>")
_CONTROL = re.compile(r"[\x00-\x08\x0b-\x1f\x7f]")
_SPACES = re.compile(r"[ \t ]+")
_MANY_NEWLINES = re.compile(r"\n{3,}")
_WHITESPACE = re.compile(r"\s+")


class Rejected(Exception):
    """Raised when a record cannot be imported. `reason` is a stable machine-readable code."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True)
class CleanReview:
    source_key: str
    review_id: int
    parent_asin: str
    asin: str
    title: str
    body: str
    rating: int
    helpful_votes: int
    verified_purchase: bool
    timestamp_ms: int
    timestamp_was_seconds: bool

    @property
    def reviewed_at_iso(self) -> str:
        dt = datetime.fromtimestamp(self.timestamp_ms / 1000, tz=timezone.utc)
        return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{self.timestamp_ms % 1000:03d}Z"


def clean_text(value: object, *, single_line: bool) -> str:
    """Strip markup the dataset contains (<br />, entities) and normalize whitespace."""
    if value is None:
        return ""
    if not isinstance(value, str):
        raise Rejected("invalid_text_type")
    text = value.replace("\r\n", "\n").replace("\r", "\n")
    text = _BR_TAG.sub("\n", text)
    text = _HTML_TAG.sub(" ", text)
    text = html.unescape(text)
    text = unicodedata.normalize("NFC", text)
    text = _CONTROL.sub("", text)
    if single_line:
        return _WHITESPACE.sub(" ", text).strip()
    lines = [_SPACES.sub(" ", line).strip() for line in text.split("\n")]
    return _MANY_NEWLINES.sub("\n\n", "\n".join(lines)).strip()


def normalize_rating(value: object) -> int:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise Rejected("invalid_rating")
    if isinstance(value, float) and (not math.isfinite(value) or not value.is_integer()):
        raise Rejected("invalid_rating")
    rating = int(value)
    if rating < 1 or rating > 5:
        raise Rejected("invalid_rating")
    return rating


def normalize_timestamp(value: object) -> tuple[int, bool]:
    """Return (milliseconds since epoch, was_seconds)."""
    if isinstance(value, bool) or not isinstance(value, int):
        raise Rejected("invalid_timestamp")
    was_seconds = 0 < value < SECONDS_THRESHOLD
    millis = value * 1000 if was_seconds else value
    if millis < MIN_TIMESTAMP_MS or millis >= MAX_TIMESTAMP_MS:
        raise Rejected("invalid_timestamp")
    return millis, was_seconds


def normalize_asin(value: object, reason: str) -> str:
    if not isinstance(value, str):
        raise Rejected(reason)
    asin = value.strip().upper()
    if not ASIN_PATTERN.match(asin):
        raise Rejected(reason)
    return asin


def normalize_helpful(value: object) -> int:
    if value is None:
        return 0
    if isinstance(value, bool) or not isinstance(value, int) or value < 0 or value > MAX_HELPFUL_VOTES:
        raise Rejected("invalid_helpful_vote")
    return value


def normalize_record(raw: dict, category: str = DATASET_CATEGORY) -> CleanReview:
    """Validate one raw record. Raises Rejected with a reason code on failure."""
    user_id = raw.get("user_id")
    if not isinstance(user_id, str) or not user_id.strip():
        raise Rejected("missing_user_id")
    rating = normalize_rating(raw.get("rating"))
    parent_asin = normalize_asin(raw.get("parent_asin"), "invalid_parent_asin")
    asin = normalize_asin(raw.get("asin"), "invalid_asin")
    timestamp_ms, was_seconds = normalize_timestamp(raw.get("timestamp"))
    helpful = normalize_helpful(raw.get("helpful_vote"))
    verified = raw.get("verified_purchase")
    if not isinstance(verified, bool):
        raise Rejected("invalid_verified_purchase")

    title = clean_text(raw.get("title"), single_line=True)
    body = clean_text(raw.get("text"), single_line=False)
    if len(title) > MAX_TITLE_CHARS:
        raise Rejected("title_too_long")
    if len(body) > MAX_BODY_CHARS:
        raise Rejected("text_too_long")
    if not title and not body:
        raise Rejected("empty_content")

    key = source_key(category, user_id.strip(), asin, timestamp_ms)
    return CleanReview(
        source_key=key,
        review_id=review_id_from_source_key(key),
        parent_asin=parent_asin,
        asin=asin,
        title=title,
        body=body,
        rating=rating,
        helpful_votes=helpful,
        verified_purchase=verified,
        timestamp_ms=timestamp_ms,
        timestamp_was_seconds=was_seconds,
    )
