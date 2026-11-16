import pytest

from rp_data.ids import DATASET_ID_FLAG, review_id_from_source_key, source_key
from rp_data.normalize import Rejected, clean_text, normalize_record, normalize_timestamp


def raw(**overrides):
    record = {
        "rating": 5.0, "title": "Great serum", "text": "Works on dry skin.<br />Would buy again &amp; again.",
        "asin": "B00YQ6X8EO", "parent_asin": "B00YQ6X8EO", "user_id": "USER1",
        "timestamp": 1588687728923, "helpful_vote": 3, "verified_purchase": True,
    }
    record.update(overrides)
    return record


def test_valid_record_is_normalized():
    clean = normalize_record(raw())
    assert clean.rating == 5
    assert clean.body == "Works on dry skin.\nWould buy again & again."
    assert clean.reviewed_at_iso == "2020-05-05T14:08:48.923Z"
    assert clean.review_id >= DATASET_ID_FLAG
    assert clean.review_id < (1 << 63)


def test_identifiers_are_deterministic_and_key_on_user_asin_time():
    a = normalize_record(raw())
    b = normalize_record(raw(title="edited title", text="different text"))
    c = normalize_record(raw(timestamp=1588687728924))
    assert a.source_key == b.source_key and a.review_id == b.review_id
    assert a.source_key != c.source_key
    assert a.source_key == source_key("All_Beauty", "USER1", "B00YQ6X8EO", 1588687728923)
    assert review_id_from_source_key(a.source_key) == a.review_id


@pytest.mark.parametrize("overrides,reason", [
    ({"rating": 0.0}, "invalid_rating"),
    ({"rating": 4.5}, "invalid_rating"),
    ({"rating": float("nan")}, "invalid_rating"),
    ({"rating": None}, "invalid_rating"),
    ({"rating": True}, "invalid_rating"),
    ({"parent_asin": "short"}, "invalid_parent_asin"),
    ({"asin": None}, "invalid_asin"),
    ({"user_id": ""}, "missing_user_id"),
    ({"timestamp": -5}, "invalid_timestamp"),
    ({"timestamp": "1588687728923"}, "invalid_timestamp"),
    ({"helpful_vote": -1}, "invalid_helpful_vote"),
    ({"verified_purchase": None}, "invalid_verified_purchase"),
    ({"title": None, "text": "   "}, "empty_content"),
    ({"title": "x" * 501}, "title_too_long"),
    ({"text": "y" * 32_001}, "text_too_long"),
    ({"text": 42}, "invalid_text_type"),
])
def test_invalid_records_are_rejected_with_reason(overrides, reason):
    with pytest.raises(Rejected) as excinfo:
        normalize_record(raw(**overrides))
    assert excinfo.value.reason == reason


def test_missing_title_or_text_is_allowed_when_other_present():
    assert normalize_record(raw(title=None)).title == ""
    assert normalize_record(raw(text=None)).body == ""


def test_timestamp_in_seconds_is_converted():
    millis, was_seconds = normalize_timestamp(1588687728)
    assert millis == 1588687728000 and was_seconds


def test_clean_text_handles_markup_controls_and_whitespace():
    assert clean_text("a<br>b<BR/>c", single_line=False) == "a\nb\nc"
    assert clean_text("I <3 this, size <5 ok", single_line=True) == "I <3 this, size <5 ok"
    assert clean_text("<b>bold</b>  text", single_line=True) == "bold text"
    assert clean_text("nul\x00byte", single_line=True) == "nulbyte"
    assert clean_text("café", single_line=True) == "café"
    assert clean_text("a\n\n\n\nb", single_line=False) == "a\n\nb"
    assert clean_text(None, single_line=True) == ""
