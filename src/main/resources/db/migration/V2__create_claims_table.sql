CREATE TABLE claims (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    deal_id UUID NOT NULL REFERENCES deals(id) ON DELETE RESTRICT,
    customer_id UUID NOT NULL,
    quantity INTEGER NOT NULL,
    amount_paise INTEGER NOT NULL,
    status VARCHAR(20) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_claims_quantity CHECK (quantity >= 1 AND quantity <= 3),
    CONSTRAINT ck_claims_amount_paise CHECK (amount_paise >= 1),
    CONSTRAINT ck_claims_status CHECK (status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW', 'EXPIRED', 'REFUND_PENDING'))
);

-- Invariant 2: Exactly one active claim (RESERVED, PAID, NEEDS_REVIEW) per customer per deal
CREATE UNIQUE INDEX idx_claims_active_per_customer_deal 
ON claims (deal_id, customer_id) 
WHERE status IN ('RESERVED', 'PAID', 'NEEDS_REVIEW');

-- Fast lookup for background expiry worker
CREATE INDEX idx_claims_reserved_expires_at 
ON claims (expires_at) 
WHERE status = 'RESERVED';

CREATE INDEX idx_claims_customer_id ON claims (customer_id);
CREATE INDEX idx_claims_deal_id ON claims (deal_id);
