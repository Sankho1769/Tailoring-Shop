-- Migration V7: Fitting appointments and bookings
CREATE TABLE IF NOT EXISTS fittings (
  id UUID PRIMARY KEY,
  booking_ref VARCHAR(32) NOT NULL UNIQUE,
  customer_name VARCHAR(160) NOT NULL,
  phone VARCHAR(30) NOT NULL,
  email VARCHAR(160),
  service_type VARCHAR(140) NOT NULL,
  booking_date DATE NOT NULL,
  time_slot VARCHAR(60) NOT NULL,
  notes TEXT,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_fittings_booking_date ON fittings(booking_date);
CREATE INDEX IF NOT EXISTS idx_fittings_status ON fittings(status);
CREATE INDEX IF NOT EXISTS idx_fittings_phone ON fittings(phone);
