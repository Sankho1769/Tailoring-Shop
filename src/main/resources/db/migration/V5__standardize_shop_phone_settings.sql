-- Migration V5: Standardize shop phone numbers and WhatsApp notification numbers
UPDATE shop_settings
SET setting_value = '9748546778',
    updated_at = NOW()
WHERE setting_key = 'shop_phone'
  AND (setting_value IS NULL OR setting_value = '' OR setting_value LIKE '%98300%');

UPDATE shop_settings
SET setting_value = '919748546778',
    updated_at = NOW()
WHERE setting_key IN ('whatsapp_number', 'admin_notification_phone')
  AND (setting_value IS NULL OR setting_value = '' OR setting_value LIKE '%98300%');
