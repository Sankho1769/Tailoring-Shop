-- ============================================================================
-- REVERSIBLE CLEANUP SCRIPT FOR CONFIRMED DEMO & TEST DATA
-- Target: H2 Database / Supabase Database for Jolly Ladies Tailor
-- ============================================================================
-- PRESERVED RECORDS (NOT TOUCHED):
-- 1. Real staff account: admin (ID: 1, Full Name: Guddi, Role: ADMIN)
-- 2. Real staff account: tailor1 (ID: 2, Role: TAILOR)
-- 3. Required shop settings: shop_name, shop_address, shop_phone, whatsapp_number, admin_notification_phone, upi_id, gpay_qr_key
-- 4. Required service categories: Stitching (1), Alteration (2), Finishing (3)
-- ============================================================================

-- 1. Clear Confirmed Sample Customer Enquiries / Requests (2 rows)
DELETE FROM customer_requests WHERE id = '9c8ee344-6469-4b04-8653-8da1e98877cc'; -- Name: Priya Roy, Phone: 9876543210
DELETE FROM customer_requests WHERE id = 'c4d5d006-fe60-4262-be18-5c2d6e1a8102'; -- Name: Sneha Mukherjee, Phone: 9748546778

-- 2. Clear Staff Work Payments & Entries Recorded on tailor1 during automated testing (14 payments, 8 work entries)
DELETE FROM staff_payments WHERE id = 'c64a4d62-962a-4737-9ef7-4eb659ec06f4'; -- Amount: 100.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = 'ff7fbd67-92eb-48cc-9bea-f2087ba9de67'; -- Amount: 700.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = '0cfcb8e3-4511-4271-b8d9-4adcabfcfc43'; -- Amount: 500.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = '3846b48c-5563-439d-ab43-5a891c287427'; -- Amount: 600.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = '467f25ea-46fe-4b1f-ad62-e1753448135a'; -- Amount: 150.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = '4b3f5dc9-dc91-417c-b540-68381a3a2954'; -- Amount: 600.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = '6f0fe2b7-99d0-47eb-9ee3-aebfb7098c7a'; -- Amount: 400.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = 'b6dc51c3-3329-4eee-8c01-6b4afdf8d157'; -- Amount: 500.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = '1f1d624b-1717-4bae-af43-a3f08d0a7e7c'; -- Amount: 150.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = '92daace5-1918-4e5d-8f1f-a7addad11704'; -- Amount: 600.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = 'f053435b-bec9-48b5-a9e8-ed88940a551f'; -- Amount: 400.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = 'da7327e9-26b9-44d1-9d52-dd75ea81aa08'; -- Amount: 500.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = 'e453cfb0-4caa-4cad-afd6-6a092f0f60b6'; -- Amount: 500.0, Date: 2026-11-04
DELETE FROM staff_payments WHERE id = '133820e2-e5d8-4fd0-9058-a316be0289eb'; -- Amount: 200.0, Date: 2026-11-04
DELETE FROM staff_work WHERE id = 'cf7af7ad-23d0-4b6a-aae6-fd0f466e83e0'; -- Description: Altered Saree Fall & Pico, Cost: 300.0
DELETE FROM staff_work WHERE id = '9f171f13-3dce-4362-91bd-6512c99a60e4'; -- Description: Stitched 3 Designer Blouses and 1 Heavy Anarkali, Cost: 1800.0
DELETE FROM staff_work WHERE id = '8e04bcb6-971e-44c5-babc-60d91af8ddc9'; -- Description: Altered 2 Salwar Suits, Cost: 400.0
DELETE FROM staff_work WHERE id = 'd02405a5-9c71-4a72-a881-c3a753510c3b'; -- Description: Stitched 3 Designer Blouses and 1 Lehenga, Cost: 1500.0
DELETE FROM staff_work WHERE id = 'bfa1534d-a8e9-4095-8c4b-76c4a3d489c7'; -- Description: Altered 2 Salwar Suits, Cost: 400.0
DELETE FROM staff_work WHERE id = 'a87a1c4e-2453-40d4-835d-5930fbca39e4'; -- Description: Stitched 3 Designer Blouses and 1 Lehenga, Cost: 1500.0
DELETE FROM staff_work WHERE id = 'f7232913-a198-44a3-8b67-30e6972ad64b'; -- Description: Stitched 3 Designer Blouses and 1 Lehenga, Cost: 1500.0
DELETE FROM staff_work WHERE id = 'a268bc36-34f0-4625-a008-4cf40dc1b6d4'; -- Description: Test Stitched 3 blouses, Cost: 600.0

-- 3. Clear Customer Order Payments recorded during testing (2 rows)
DELETE FROM payments WHERE id = '69f7fd6e-5db1-4074-8e2c-725eff9d00f6'; -- Order: JLT-20261104-64445, Amount: 80.0
DELETE FROM payments WHERE id = 'dfcf879f-b78d-405d-bf8b-eda185491e07'; -- Order: JLT-20261028-B55EF, Amount: 120.0

-- 4. Clear Test Orders & Order Items (11 orders)
DELETE FROM order_items WHERE order_id = '671fced6-90ce-4747-94d1-ae0703a4dd7e';
DELETE FROM tailoring_orders WHERE id = '671fced6-90ce-4747-94d1-ae0703a4dd7e'; -- Order: JLT-20261104-ACEFD, Customer: Rupali Mukherjee
DELETE FROM order_items WHERE order_id = 'f1e95adc-6272-43cb-a5e8-dda32f82a1b3';
DELETE FROM tailoring_orders WHERE id = 'f1e95adc-6272-43cb-a5e8-dda32f82a1b3'; -- Order: JLT-20261104-9C6FF, Customer: Rupali Mukherjee
DELETE FROM order_items WHERE order_id = '7ff4b248-fd8c-4426-9cc4-41918491caef';
DELETE FROM tailoring_orders WHERE id = '7ff4b248-fd8c-4426-9cc4-41918491caef'; -- Order: JLT-20261104-2BA47, Customer: Rupali Mukherjee
DELETE FROM order_items WHERE order_id = 'ac00fba8-c4d8-46d3-a6bc-7c8dc97aedac';
DELETE FROM tailoring_orders WHERE id = 'ac00fba8-c4d8-46d3-a6bc-7c8dc97aedac'; -- Order: JLT-20261104-33CEF, Customer: Rupali Mukherjee
DELETE FROM order_items WHERE order_id = '519acf21-9547-4477-9c3f-499be7004e8d';
DELETE FROM tailoring_orders WHERE id = '519acf21-9547-4477-9c3f-499be7004e8d'; -- Order: JLT-20261104-20E59, Customer: Rupali Mukherjee
DELETE FROM order_items WHERE order_id = 'da9b74d4-1008-4a77-a513-db06e6739dbe';
DELETE FROM tailoring_orders WHERE id = 'da9b74d4-1008-4a77-a513-db06e6739dbe'; -- Order: JLT-20261104-64445, Customer: Sharmila Banerjee
DELETE FROM order_items WHERE order_id = '50b38b83-67a3-4039-a94d-656f75ae40ca';
DELETE FROM tailoring_orders WHERE id = '50b38b83-67a3-4039-a94d-656f75ae40ca'; -- Order: JLT-20261028-B55EF, Customer: Shubham Banerjee
DELETE FROM order_items WHERE order_id = '87f56bf2-4cdd-4109-80c0-bff8efb5f375';
DELETE FROM tailoring_orders WHERE id = '87f56bf2-4cdd-4109-80c0-bff8efb5f375'; -- Order: JLT-20261028-D696B, Customer: Shubham Banerjee
DELETE FROM order_items WHERE order_id = 'c1f1e750-05bc-482f-ab96-49acefcbbcba';
DELETE FROM tailoring_orders WHERE id = 'c1f1e750-05bc-482f-ab96-49acefcbbcba'; -- Order: JLT-20261028-9E581, Customer: Shubham Banerjee
DELETE FROM order_items WHERE order_id = '8962f7c7-7b15-4ea0-bd0f-45d7081d035b';
DELETE FROM tailoring_orders WHERE id = '8962f7c7-7b15-4ea0-bd0f-45d7081d035b'; -- Order: JLT-20261028-38CE8, Customer: Shubham Banerjee
DELETE FROM order_items WHERE order_id = '3bc959df-7dd3-4d35-bbd5-0461012babf2';
DELETE FROM tailoring_orders WHERE id = '3bc959df-7dd3-4d35-bbd5-0461012babf2'; -- Order: JLT-20261028-F7308, Customer: Sunita Sen

-- 5. Clear Test Measurements attached to test customers
DELETE FROM measurements WHERE id = 'a635a0b4-1b28-48cc-8ef1-491164f19a36'; -- Garment: Kurti, Cust: Shubham Banerjee
DELETE FROM measurements WHERE id = 'ba1b3401-dcfb-4cbf-a70e-fa3043b3b6e0'; -- Garment: PANT, Cust: Rupali Mukherjee
DELETE FROM measurements WHERE id = 'a1c9f8b6-72fc-4d71-82f0-6e6943eb3335'; -- Garment: CHURIDAR / BLOUSE, Cust: Rupali Mukherjee
DELETE FROM measurements WHERE id = '33c9a187-c55c-4c84-b4c2-006a98846579'; -- Garment: PANT, Cust: Rupali Mukherjee
DELETE FROM measurements WHERE id = '9af3c2a1-f4a1-4aff-ad08-8ec757efa43d'; -- Garment: CHURIDAR / BLOUSE, Cust: Rupali Mukherjee
DELETE FROM measurements WHERE id = 'c5279e5d-32ed-4f8c-9d3d-722bf143cca9'; -- Garment: PANT, Cust: Rupali Mukherjee
DELETE FROM measurements WHERE id = '776c2848-456c-4573-9fc0-3c41758215d4'; -- Garment: CHURIDAR / BLOUSE, Cust: Rupali Mukherjee
DELETE FROM measurements WHERE id = 'df9dc7b2-4107-4289-81bb-8c2e1121ab93'; -- Garment: PANT, Cust: Rupali Mukherjee
DELETE FROM measurements WHERE id = '0d694b1c-7e79-4eea-8d96-8bf5208ec5c8'; -- Garment: CHURIDAR / BLOUSE, Cust: Rupali Mukherjee

-- 6. Clear Test Customers (9 rows)
DELETE FROM customers WHERE id = '3559e853-d44c-465f-b7f6-823b93d6da64'; -- Name: Shubham Banerjee, Phone: 8276834031
DELETE FROM customers WHERE id = '6ea65c36-34bf-4107-8456-e6db87b65def'; -- Name: Rupali Mukherjee, Phone: 9831482553
DELETE FROM customers WHERE id = '1c3c00bd-bf12-4616-909a-8b365a85db71'; -- Name: Rupali Mukherjee, Phone: 9831482399
DELETE FROM customers WHERE id = '4e5962c7-3ea0-43b8-abe5-65fc5104d24d'; -- Name: Rupali Mukherjee, Phone: 9831482265
DELETE FROM customers WHERE id = '9b6d3736-a8c2-421a-bb5d-87b29efba607'; -- Name: Rupali Mukherjee, Phone: 9831482765
DELETE FROM customers WHERE id = 'dd962e74-3630-4a85-839f-6bbfd9f0417f'; -- Name: Sharmila Banerjee, Phone: 9831179661
DELETE FROM customers WHERE id = 'dff5a9bf-aa96-4d5f-b844-3bbd5883c2b9'; -- Name: Sunita Sen, Phone: 9831122334
DELETE FROM customers WHERE id = '2fd3ceb1-f843-45c1-bad8-f64fa3d505ac'; -- Name: Test Customer, Phone: 9831122334
DELETE FROM customers WHERE id = 'aa361eb5-4298-4c75-8281-996687477c1b'; -- Name: Test Customer 2, Phone: +919831122334

-- 7. Clear Synthetic Sample Products from data.sql (8 rows)
DELETE FROM products WHERE id = 'a0000000-0000-0000-0000-000000000008'; -- Name: Alteration, Category: Alteration
DELETE FROM products WHERE id = 'a0000000-0000-0000-0000-000000000001'; -- Name: False par, Category: Finishing
DELETE FROM products WHERE id = 'a0000000-0000-0000-0000-000000000002'; -- Name: Peko, Category: Finishing
DELETE FROM products WHERE id = 'a0000000-0000-0000-0000-000000000003'; -- Name: Blouse, Category: Stitching
DELETE FROM products WHERE id = 'a0000000-0000-0000-0000-000000000006'; -- Name: Frock, Category: Stitching
DELETE FROM products WHERE id = 'a0000000-0000-0000-0000-000000000007'; -- Name: Lehenga, Category: Stitching
DELETE FROM products WHERE id = 'a0000000-0000-0000-0000-000000000005'; -- Name: Pant, Category: Stitching
DELETE FROM products WHERE id = 'a0000000-0000-0000-0000-000000000004'; -- Name: Salwar, Category: Stitching

-- 8. Clear Auxiliary Test Admin User (if present)
DELETE FROM app_users WHERE username = 'admin_user';
