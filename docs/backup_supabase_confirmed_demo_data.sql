-- ============================================================================
-- RESTORABLE BACKUP OF CONFIRMED DEMO RECORDS IN SUPABASE
-- Generated: 2026-11-04
-- Target: Supabase PostgreSQL (Jolly Ladies Tailor)
-- Total Records: 7 (3 customers, 1 order, 1 order_item, 2 enquiries)
-- ============================================================================
-- RESTORATION INSTRUCTIONS:
-- In the event of a rollback, execute this file in PostgreSQL / Supabase SQL Editor.
-- The statements insert records in strict foreign-key dependency order (parents first).
-- ============================================================================

-- 1. Restore Customers (3 rows)
INSERT INTO customers (id, name, phone, address, notes, created_at) VALUES
  ('2fd3ceb1-f843-45c1-bad8-f64fa3d505ac', 'Test Customer', '9831122334', 'Test Address', 'Test Notes', '2026-10-28 22:12:50.665941+00'),
  ('aa361eb5-4298-4c75-8281-996687477c1b', 'Test Customer 2', '+919831122334', 'Test Address 2', 'Test Notes 2', '2026-10-28 22:12:50.697373+00'),
  ('dff5a9bf-aa96-4d5f-b844-3bbd5883c2b9', 'Sunita Sen', '9831122334', 'Southern Ave', 'Cotton', '2026-10-28 22:33:59.396473+00')
ON CONFLICT (id) DO NOTHING;

-- 2. Restore Tailoring Order (1 row)
INSERT INTO tailoring_orders (id, order_number, customer_id, measurement_id, status, payment_status, subtotal, discount, total, amount_paid, due_date, created_at) VALUES
  ('3bc959df-7dd3-4d35-bbd5-0461012babf2', 'JLT-20261028-F7308', 'dff5a9bf-aa96-4d5f-b844-3bbd5883c2b9', NULL, 'NEW', 'PAYMENT_DUE', 650.00, 0.00, 650.00, 0.00, '2026-11-10', '2026-10-28 22:34:18.468767+00')
ON CONFLICT (id) DO NOTHING;

-- 3. Restore Order Item (1 row)
INSERT INTO order_items (id, order_id, product_id, item_name, quantity, unit_rate, amount) VALUES
  ('3cd4a464-0794-4938-b515-81212535a65b', '3bc959df-7dd3-4d35-bbd5-0461012babf2', NULL, 'Designer Blouse', 1, 650.00, 650.00)
ON CONFLICT (id) DO NOTHING;

-- 4. Restore Customer Requests / Enquiries (2 rows)
INSERT INTO customer_requests (id, name, phone, email, requested_service, message, status, created_at) VALUES
  ('9c8ee344-6469-4b04-8653-8da1e98877cc', 'Priya Roy', '9876543210', 'priya@example.com', NULL, 'Need bridal blouse stitching', 'NEW', '2026-10-28 21:57:45.974901+00'),
  ('c4d5d006-fe60-4262-be18-5c2d6e1a8102', 'Sneha Mukherjee', '9748546778', 'sneha@example.com', 'Blouse Stitching', 'Looking for a designer blouse stitching before Sunday.', 'NEW', '2026-10-28 21:46:54.191243+00')
ON CONFLICT (id) DO NOTHING;
