-- ============================================================================
-- SAFE TRANSACTIONAL CLEANUP OF CONFIRMED DEMO RECORDS IN SUPABASE
-- Generated: 2026-11-04
-- Target: Supabase PostgreSQL (Jolly Ladies Tailor)
-- Scope: ONLY 7 confirmed demo rows.
-- PRESERVED:
--   - Customer Shubham Banerjee (ID: 3559e853-d44c-465f-b7f6-823b93d6da64) and all 4 orders, 6 items, 1 payment, 1 measurement.
--   - All app_users accounts (admin, tailor1, admin_user).
--   - All categories (Stitching, Alteration, Finishing).
--   - All products.
--   - All shop_settings (including shop_address).
-- ============================================================================

BEGIN;

-- 1. Delete dependent order items for confirmed demo orders (1 row)
DELETE FROM order_items
WHERE id = '3cd4a464-0794-4938-b515-81212535a65b'
  AND order_id = '3bc959df-7dd3-4d35-bbd5-0461012babf2';

-- 2. Delete confirmed demo tailoring orders (1 row)
DELETE FROM tailoring_orders
WHERE id = '3bc959df-7dd3-4d35-bbd5-0461012babf2'
  AND customer_id = 'dff5a9bf-aa96-4d5f-b844-3bbd5883c2b9';

-- 3. Delete confirmed demo customers (3 rows)
DELETE FROM customers
WHERE id IN (
  '2fd3ceb1-f843-45c1-bad8-f64fa3d505ac', -- Test Customer
  'aa361eb5-4298-4c75-8281-996687477c1b', -- Test Customer 2
  'dff5a9bf-aa96-4d5f-b844-3bbd5883c2b9'  -- Sunita Sen
);

-- 4. Delete confirmed demo customer requests / enquiries (2 rows)
DELETE FROM customer_requests
WHERE id IN (
  '9c8ee344-6469-4b04-8653-8da1e98877cc', -- Priya Roy
  'c4d5d006-fe60-4262-be18-5c2d6e1a8102'  -- Sneha Mukherjee
);

COMMIT;
