CREATE TABLE payment_workflows (
    correlation_id VARCHAR(36) PRIMARY KEY,
    client_id INTEGER,
    amount INTEGER CHECK (amount >= 0),
    state VARCHAR(24) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT payment_workflows_state_check
        CHECK (state IN ('PREPARING', 'PREPARED', 'REJECTED', 'COMMITTED', 'ABORTED'))
);

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
