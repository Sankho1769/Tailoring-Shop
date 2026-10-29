-- Migration V2: Order status standardization and payment status tracking

ALTER TABLE tailoring_orders
  ADD COLUMN IF NOT EXISTS payment_status VARCHAR(30) NOT NULL DEFAULT 'PAYMENT_DUE';

-- Backfill payment status for any existing orders
UPDATE tailoring_orders
SET payment_status = CASE
  WHEN total > 0 AND amount_paid >= total THEN 'PAID'
  WHEN amount_paid > 0 AND amount_paid < total THEN 'PARTIALLY_PAID'
  ELSE 'PAYMENT_DUE'
END
WHERE payment_status = 'PAYMENT_DUE';

-- Update existing RECEIVED status to NEW
UPDATE tailoring_orders SET status = 'NEW' WHERE status = 'RECEIVED';
ALTER TABLE tailoring_orders ALTER COLUMN status SET DEFAULT 'NEW';

-- Add index on order status and payment status
CREATE INDEX IF NOT EXISTS idx_orders_status ON tailoring_orders(status);
CREATE INDEX IF NOT EXISTS idx_orders_payment_status ON tailoring_orders(payment_status);

-- Enhance payments table with payment_type ('PAYMENT', 'REFUND')
ALTER TABLE payments ADD COLUMN IF NOT EXISTS payment_type VARCHAR(20) NOT NULL DEFAULT 'PAYMENT';

-- Add index on payment received date
CREATE INDEX IF NOT EXISTS idx_payments_received_at ON payments(received_at DESC);
