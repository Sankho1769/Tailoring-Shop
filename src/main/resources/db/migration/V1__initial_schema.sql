
CREATE TABLE IF NOT EXISTS shop_settings (
  setting_key VARCHAR(100) PRIMARY KEY,
  setting_value TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO shop_settings(setting_key,setting_value) VALUES
 ('shop_name','Jolly Ladies Tailor'),('shop_address',''),('shop_phone',''),('whatsapp_number',''),('upi_id',''),('gpay_qr_key','')
ON CONFLICT(setting_key) DO NOTHING;

CREATE TABLE IF NOT EXISTS app_users (
  id BIGSERIAL PRIMARY KEY,
  username VARCHAR(80) NOT NULL UNIQUE,
  password_hash VARCHAR(100) NOT NULL,
  role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN','TAILOR')),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS customers (
  id UUID PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  phone VARCHAR(30) NOT NULL,
  address TEXT,
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_customers_phone ON customers(phone);
CREATE INDEX IF NOT EXISTS idx_customers_name ON customers(name);
CREATE TABLE IF NOT EXISTS measurements (
  id UUID PRIMARY KEY,
  customer_id UUID NOT NULL REFERENCES customers(id) ON DELETE CASCADE,
  garment_type VARCHAR(100) NOT NULL,
  unit VARCHAR(10) NOT NULL DEFAULT 'in',
  dimensions JSONB NOT NULL DEFAULT '{}'::jsonb,
  fit_preference VARCHAR(100),
  notes TEXT,
  created_by BIGINT REFERENCES app_users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_measurements_customer_date ON measurements(customer_id, created_at DESC);
CREATE TABLE IF NOT EXISTS categories (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL UNIQUE,
  description TEXT,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS products (
  id UUID PRIMARY KEY,
  category_id BIGINT REFERENCES categories(id) ON DELETE SET NULL,
  name VARCHAR(140) NOT NULL UNIQUE,
  description TEXT,
  default_rate NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (default_rate >= 0),
  image_key TEXT,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS customer_requests (
  id UUID PRIMARY KEY,
  name VARCHAR(160) NOT NULL,
  phone VARCHAR(30) NOT NULL,
  requested_service VARCHAR(140),
  message TEXT,
  status VARCHAR(30) NOT NULL DEFAULT 'NEW',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE TABLE IF NOT EXISTS tailoring_orders (
  id UUID PRIMARY KEY,
  order_number VARCHAR(32) NOT NULL UNIQUE,
  customer_id UUID NOT NULL REFERENCES customers(id),
  measurement_id UUID REFERENCES measurements(id) ON DELETE SET NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'RECEIVED',
  due_date DATE,
  notes TEXT,
  subtotal NUMERIC(12,2) NOT NULL DEFAULT 0,
  discount NUMERIC(12,2) NOT NULL DEFAULT 0,
  total NUMERIC(12,2) NOT NULL DEFAULT 0,
  amount_paid NUMERIC(12,2) NOT NULL DEFAULT 0,
  created_by BIGINT REFERENCES app_users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (total >= 0), CHECK (amount_paid >= 0)
);
CREATE INDEX IF NOT EXISTS idx_orders_created ON tailoring_orders(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_orders_customer ON tailoring_orders(customer_id, created_at DESC);
CREATE TABLE IF NOT EXISTS order_items (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES tailoring_orders(id) ON DELETE CASCADE,
  product_id UUID REFERENCES products(id) ON DELETE SET NULL,
  item_name VARCHAR(140) NOT NULL,
  quantity INTEGER NOT NULL CHECK (quantity > 0),
  unit_rate NUMERIC(12,2) NOT NULL CHECK (unit_rate >= 0),
  amount NUMERIC(12,2) NOT NULL CHECK (amount >= 0)
);
CREATE TABLE IF NOT EXISTS payments (
  id UUID PRIMARY KEY,
  order_id UUID NOT NULL REFERENCES tailoring_orders(id) ON DELETE CASCADE,
  amount NUMERIC(12,2) NOT NULL CHECK (amount > 0),
  method VARCHAR(30) NOT NULL,
  reference VARCHAR(160),
  note TEXT,
  received_by BIGINT REFERENCES app_users(id),
  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_payments_order ON payments(order_id, received_at DESC);
INSERT INTO categories(name, description) VALUES
 ('Stitching','Garment stitching services'),('Alteration','Garment alteration services'),('Finishing','Peko, false par and finishing services')
ON CONFLICT (name) DO NOTHING;
INSERT INTO products(id, category_id, name, default_rate) VALUES
 (gen_random_uuid(), (SELECT id FROM categories WHERE name='Finishing'), 'False par', 0),
 (gen_random_uuid(), (SELECT id FROM categories WHERE name='Finishing'), 'Peko', 0),
 (gen_random_uuid(), (SELECT id FROM categories WHERE name='Stitching'), 'Blouse', 0),
 (gen_random_uuid(), (SELECT id FROM categories WHERE name='Stitching'), 'Salwar', 0),
 (gen_random_uuid(), (SELECT id FROM categories WHERE name='Stitching'), 'Pant', 0),
 (gen_random_uuid(), (SELECT id FROM categories WHERE name='Stitching'), 'Frock', 0),
 (gen_random_uuid(), (SELECT id FROM categories WHERE name='Stitching'), 'Lehenga', 0),
 (gen_random_uuid(), (SELECT id FROM categories WHERE name='Alteration'), 'Alteration', 0)
ON CONFLICT (name) DO NOTHING;
