-- Authoritative review storage.
--
-- Identifier scheme (see docs/decisions/0002-review-identifiers.md):
--   * Dataset/replay reviews: id = (first 62 bits of SHA-256(source_key)) | 2^62  -> [2^62, 2^63)
--   * API-created reviews:    id = nextval('review_api_id_seq')                    -> [1, 2^62)
--   The ranges are disjoint, so API ids can never collide with dataset ids.
--   source_key = SHA-256 hex of "All_Beauty|<user_id>|<asin>|<timestamp_ms>" (the raw user_id is never stored).

CREATE SEQUENCE review_api_id_seq START WITH 1 MINVALUE 1 MAXVALUE 4611686018427387903 NO CYCLE;

CREATE TABLE import_runs (
    id             BIGSERIAL    PRIMARY KEY,
    started_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at    TIMESTAMPTZ,
    status         VARCHAR(16)  NOT NULL,
    source_file    TEXT         NOT NULL,
    source_sha256  CHAR(64),
    row_limit      BIGINT,
    batch_size     INTEGER      NOT NULL,
    processed      BIGINT       NOT NULL DEFAULT 0,
    inserted       BIGINT       NOT NULL DEFAULT 0,
    rejected       BIGINT       NOT NULL DEFAULT 0,
    duplicates     BIGINT       NOT NULL DEFAULT 0,
    reject_reasons JSONB        NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT import_runs_status CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED')),
    CONSTRAINT import_runs_batch CHECK (batch_size > 0)
);

CREATE TABLE reviews (
    id                 BIGINT       PRIMARY KEY,
    source             VARCHAR(16)  NOT NULL,
    source_key         CHAR(64)     UNIQUE,
    parent_asin        VARCHAR(10)  NOT NULL,
    asin               VARCHAR(10),
    title              TEXT         NOT NULL DEFAULT '',
    body               TEXT         NOT NULL DEFAULT '',
    rating             SMALLINT     NOT NULL,
    helpful_votes      INTEGER      NOT NULL DEFAULT 0,
    verified_purchase  BOOLEAN      NOT NULL DEFAULT FALSE,
    reviewed_at        TIMESTAMPTZ  NOT NULL,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version            BIGINT       NOT NULL DEFAULT 1,
    deleted            BOOLEAN      NOT NULL DEFAULT FALSE,
    deleted_at         TIMESTAMPTZ,
    import_run_id      BIGINT       REFERENCES import_runs (id),
    CONSTRAINT reviews_source CHECK (source IN ('DATASET', 'REPLAY', 'API')),
    CONSTRAINT reviews_source_key_required CHECK (source = 'API' OR source_key IS NOT NULL),
    CONSTRAINT reviews_rating_range CHECK (rating BETWEEN 1 AND 5),
    CONSTRAINT reviews_helpful_nonneg CHECK (helpful_votes >= 0),
    CONSTRAINT reviews_version_pos CHECK (version >= 1),
    CONSTRAINT reviews_title_len CHECK (char_length(title) <= 500),
    CONSTRAINT reviews_body_len CHECK (char_length(body) <= 32000),
    CONSTRAINT reviews_has_content CHECK (char_length(title) > 0 OR char_length(body) > 0),
    CONSTRAINT reviews_parent_asin_fmt CHECK (parent_asin ~ '^[A-Z0-9]{10}$'),
    CONSTRAINT reviews_asin_fmt CHECK (asin IS NULL OR asin ~ '^[A-Z0-9]{10}$'),
    CONSTRAINT reviews_deleted_at CHECK ((deleted AND deleted_at IS NOT NULL) OR (NOT deleted AND deleted_at IS NULL))
);

CREATE INDEX reviews_parent_asin_idx ON reviews (parent_asin);
CREATE INDEX reviews_reviewed_at_idx ON reviews (reviewed_at);
