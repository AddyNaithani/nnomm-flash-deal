CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE deals (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    restaurant_id UUID NOT NULL,
    title VARCHAR(100) NOT NULL,
    price_paise INTEGER NOT NULL,
    stock_total INTEGER NOT NULL,
    stock_available INTEGER NOT NULL,
    starts_at TIMESTAMPTZ NOT NULL,
    ends_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_deals_price_paise CHECK (price_paise >= 1 AND price_paise <= 1000000),
    CONSTRAINT ck_deals_stock_total CHECK (stock_total >= 1 AND stock_total <= 10000),
    CONSTRAINT ck_deals_stock_available CHECK (stock_available >= 0 AND stock_available <= stock_total),
    CONSTRAINT ck_deals_time_window CHECK (starts_at < ends_at),
    CONSTRAINT ck_deals_title_len CHECK (char_length(title) >= 1 AND char_length(title) <= 100)
);

CREATE INDEX idx_deals_restaurant_id ON deals (restaurant_id);
