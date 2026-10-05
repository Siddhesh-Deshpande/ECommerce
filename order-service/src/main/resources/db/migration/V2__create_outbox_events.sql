CREATE TABLE outbox_events (
    event_id BIGSERIAL PRIMARY KEY,
    topic VARCHAR(128) NOT NULL,
    message_key VARCHAR(128) NOT NULL,
    deduplication_key VARCHAR(240) NOT NULL UNIQUE,
    payload_type VARCHAR(80) NOT NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    last_error TEXT
);

CREATE INDEX outbox_events_pending_idx
    ON outbox_events (event_id)
    WHERE published_at IS NULL;
