-- Migration V4: Staff management enhancements and audit fields
ALTER TABLE app_users
  ADD COLUMN IF NOT EXISTS full_name VARCHAR(160),
  ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMPTZ;

-- Backfill default full names for existing standard accounts
UPDATE app_users SET full_name = 'Shop Administrator' WHERE username = 'admin' AND (full_name IS NULL OR full_name = '');
UPDATE app_users SET full_name = 'Master Tailor' WHERE username IN ('tailor', 'tailor1') AND (full_name IS NULL OR full_name = '');
