MERGE INTO shop_settings (setting_key, setting_value) KEY (setting_key) VALUES
 ('shop_name', 'Jolly Ladies Tailor'),
 ('shop_address', '12/A Southern Avenue, Kolkata'),
 ('shop_phone', '+91 98300 12345'),
 ('whatsapp_number', '919830012345'),
 ('upi_id', 'jollytailor@okaxis'),
 ('gpay_qr_key', '');

MERGE INTO categories (name, description, active) KEY (name) VALUES
 ('Stitching', 'Garment stitching services', TRUE),
 ('Alteration', 'Garment alteration services', TRUE),
 ('Finishing', 'Peko, false par and finishing services', TRUE);

MERGE INTO products (id, category_id, name, default_rate, active) KEY (name) VALUES
 ('a0000000-0000-0000-0000-000000000001', 3, 'False par', 0, TRUE),
 ('a0000000-0000-0000-0000-000000000002', 3, 'Peko', 0, TRUE),
 ('a0000000-0000-0000-0000-000000000003', 1, 'Blouse', 0, TRUE),
 ('a0000000-0000-0000-0000-000000000004', 1, 'Salwar', 0, TRUE),
 ('a0000000-0000-0000-0000-000000000005', 1, 'Pant', 0, TRUE),
 ('a0000000-0000-0000-0000-000000000006', 1, 'Frock', 0, TRUE),
 ('a0000000-0000-0000-0000-000000000007', 1, 'Lehenga', 0, TRUE),
 ('a0000000-0000-0000-0000-000000000008', 2, 'Alteration', 0, TRUE);
