MERGE INTO shop_settings (setting_key, setting_value) KEY (setting_key) VALUES
 ('shop_name', 'Jolly Ladies Tailor'),
 ('shop_address', '12/A Southern Avenue, Kolkata - 700029'),
 ('shop_phone', '9748546778'),
 ('whatsapp_number', '919748546778'),
 ('admin_notification_phone', '919748546778'),
 ('upi_id', 'jollytailor@okaxis'),
 ('gpay_qr_key', '');

MERGE INTO categories (name, description, active) KEY (name) VALUES
 ('Stitching', 'Garment stitching services', TRUE),
 ('Alteration', 'Garment alteration services', TRUE),
 ('Finishing', 'Peko, false par and finishing services', TRUE);

MERGE INTO products (id, category_id, name, default_rate, active) KEY (name) VALUES
 ('a0000000-0000-0000-0000-000000000001', 3, 'False par', 60.00, TRUE),
 ('a0000000-0000-0000-0000-000000000002', 3, 'Peko', 40.00, TRUE),
 ('a0000000-0000-0000-0000-000000000003', 1, 'Blouse', 350.00, TRUE),
 ('a0000000-0000-0000-0000-000000000004', 1, 'Salwar', 250.00, TRUE),
 ('a0000000-0000-0000-0000-000000000005', 1, 'Pant', 300.00, TRUE),
 ('a0000000-0000-0000-0000-000000000006', 1, 'Frock', 450.00, TRUE),
 ('a0000000-0000-0000-0000-000000000007', 1, 'Lehenga', 1200.00, TRUE),
 ('a0000000-0000-0000-0000-000000000008', 2, 'Alteration', 80.00, TRUE);
