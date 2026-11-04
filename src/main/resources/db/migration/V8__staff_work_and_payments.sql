-- Migration V8: Staff work assignments, tracking, and actual payment ledger

CREATE TABLE IF NOT EXISTS staff_work (
    id UUID PRIMARY KEY,
    staff_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    order_id UUID REFERENCES tailoring_orders(id) ON DELETE SET NULL,
    work_date DATE NOT NULL DEFAULT CURRENT_DATE,
    description VARCHAR(255) NOT NULL,
    amount_owed NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (amount_owed >= 0),
    notes TEXT,
    recorded_by BIGINT REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_staff_work_staff_id ON staff_work(staff_id);
CREATE INDEX IF NOT EXISTS idx_staff_work_date ON staff_work(work_date);

CREATE TABLE IF NOT EXISTS staff_payments (
    id UUID PRIMARY KEY,
    staff_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE,
    work_id UUID REFERENCES staff_work(id) ON DELETE CASCADE,
    payment_date DATE NOT NULL DEFAULT CURRENT_DATE,
    amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    payment_method VARCHAR(50) NOT NULL DEFAULT 'CASH',
    reference VARCHAR(160),
    notes TEXT,
    recorded_by BIGINT REFERENCES app_users(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_staff_payments_work_id ON staff_payments(work_id);
CREATE INDEX IF NOT EXISTS idx_staff_payments_staff_id ON staff_payments(staff_id);
CREATE INDEX IF NOT EXISTS idx_staff_payments_date ON staff_payments(payment_date);
