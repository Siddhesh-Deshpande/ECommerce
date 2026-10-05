ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(36);

CREATE UNIQUE INDEX IF NOT EXISTS orders_correlation_id_key
    ON orders (correlation_id);
