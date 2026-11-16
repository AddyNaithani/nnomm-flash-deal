CREATE TABLE processed_webhook_events (
    event_id VARCHAR(128) PRIMARY KEY,
    claim_id UUID NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    amount_paise INTEGER NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_processed_webhook_claim_id ON processed_webhook_events (claim_id);
