MERGE INTO shop_settings (setting_key, setting_value) KEY (setting_key) VALUES
 ('shop_name', 'Jolly Ladies Tailor'),
 ('shop_address', '6/1 Banerjee Para Road, Kolkata - 700061'),
 ('shop_phone', '9748546778'),
 ('whatsapp_number', '919748546778'),
 ('admin_notification_phone', '919748546778'),
 ('upi_id', 'jollytailor@okaxis'),
 ('gpay_qr_key', '');

MERGE INTO categories (name, description, active) KEY (name) VALUES
 ('Stitching', 'Garment stitching services', TRUE),
 ('Alteration', 'Garment alteration services', TRUE),
 ('Finishing', 'Peko, false par and finishing services', TRUE);

