-- Transactional outbox. A row is inserted in the SAME transaction as the review change it describes,
-- so an event exists if and only if the change committed. The publisher moves rows to Kafka afterwards.

CREATE TABLE outbox_events (
    id              BIGSERIAL    PRIMARY KEY,
    event_id        UUID         NOT NULL UNIQUE,
    review_id       BIGINT       NOT NULL,
    review_version  BIGINT       NOT NULL,
    event_type      VARCHAR(32)  NOT NULL,
    payload         JSONB        NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER      NOT NULL DEFAULT 0,
    last_error      TEXT,
    published_at    TIMESTAMPTZ,
    CONSTRAINT outbox_event_type CHECK (event_type IN
        ('REVIEW_CREATED', 'REVIEW_UPDATED', 'REVIEW_DELETED', 'HELPFULNESS_UPDATED')),
    CONSTRAINT outbox_status CHECK (status IN ('PENDING', 'PUBLISHED', 'FAILED')),
    CONSTRAINT outbox_published_at CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL)),
    -- Exactly one event per committed review version.
    CONSTRAINT outbox_one_event_per_version UNIQUE (review_id, review_version)
);

-- The publisher only ever scans pending rows in id order; keep that scan tiny.
CREATE INDEX outbox_pending_idx ON outbox_events (id) WHERE status = 'PENDING';
CREATE INDEX outbox_published_at_idx ON outbox_events (published_at) WHERE status = 'PUBLISHED';
