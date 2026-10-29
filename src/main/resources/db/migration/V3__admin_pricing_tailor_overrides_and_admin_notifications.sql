-- Migration V3: Admin pricing controls, tailor price adjustments audit, ready-made stock, and admin notifications

-- Add price adjustment tracking to order_items
ALTER TABLE order_items
  ADD COLUMN IF NOT EXISTS default_rate NUMERIC(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS discount NUMERIC(12,2) NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS adjustment_reason VARCHAR(255),
  ADD COLUMN IF NOT EXISTS adjusted_by BIGINT REFERENCES app_users(id),
  ADD COLUMN IF NOT EXISTS adjusted_at TIMESTAMPTZ;

-- Backfill default_rate on existing order_items
UPDATE order_items SET default_rate = unit_rate WHERE default_rate = 0;

-- Support ready-made stock products and stock quantity
ALTER TABLE products
  ADD COLUMN IF NOT EXISTS product_type VARCHAR(30) NOT NULL DEFAULT 'SERVICE',
  ADD COLUMN IF NOT EXISTS stock_quantity INTEGER NOT NULL DEFAULT 0;

-- Add admin notification phone to shop_settings
INSERT INTO shop_settings(setting_key, setting_value)
VALUES ('admin_notification_phone', '919748546778')
ON CONFLICT (setting_key) DO NOTHING;

-- Add email column to customer_requests
ALTER TABLE customer_requests
  ADD COLUMN IF NOT EXISTS email VARCHAR(160);
