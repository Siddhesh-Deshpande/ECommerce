CREATE TABLE coordinator_workflows (
    correlation_id VARCHAR(36) PRIMARY KEY,
    client_id INTEGER NOT NULL,
    request_json TEXT NOT NULL,
    state VARCHAR(24) NOT NULL,
    order_id INTEGER,
    order_vote BOOLEAN,
    inventory_vote BOOLEAN,
    payment_vote BOOLEAN,
    order_commit_ack BOOLEAN,
    inventory_commit_ack BOOLEAN,
    payment_commit_ack BOOLEAN,
    order_abort_ack BOOLEAN,
    inventory_abort_ack BOOLEAN,
    payment_abort_ack BOOLEAN,
    decision_attempt INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT coordinator_workflows_state_check
        CHECK (state IN ('PREPARING', 'COMMITTING', 'ABORTING', 'COMPLETED', 'ABORTED', 'RECOVERY_REQUIRED'))
);

CREATE INDEX coordinator_workflows_recovery_idx
    ON coordinator_workflows (created_at)
    WHERE state = 'PREPARING';

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
