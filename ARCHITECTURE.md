# Architecture & role boundaries

```text
Consumer browser (/)
  ├─ public catalogue and shop profile
  └─ appointment/enquiry request ──────────────┐
                                                │
Tailor browser (/tailor.html)                   │
  ├─ secure session + role TAILOR               │
  ├─ customer records                           │
  ├─ customer-specific measurement snapshots    │
  ├─ orders / bills / order status              │
  └─ manually confirmed payment records         │
                                                ▼
Admin browser (/admin.html)              Java Spring Boot REST API
  ├─ secure session + role ADMIN                ├─ server-side validation
  ├─ business statistics                         ├─ authorization per route
  ├─ product/category management                 ├─ transactional order/payment updates
  ├─ staff account management                    └─ Supabase Storage uploads
  └─ shop settings and GPay QR                               │
                                                             ├── PostgreSQL
                                                             │   customers
                                                             │   measurements
                                                             │   products/categories
                                                             │   orders/order_items
                                                             │   payments
                                                             │   consumer_requests
                                                             │   app_users/shop_settings
                                                             └── Supabase Storage
                                                                 public catalog/QR images
```

## Role rules

- Consumer endpoints only expose active service catalogue and submit enquiries. They cannot fetch customer records, measurements, orders, reports or admin data.
- Tailor endpoints require an authenticated `TAILOR` or `ADMIN` session.
- Admin endpoints require an authenticated `ADMIN` session.
- Passwords are BCrypt hashes; session-mutating requests use a CSRF cookie/header pair.
- Supabase S3 credentials remain in backend environment variables, not HTML or JavaScript.

## Data flow

A tailor saves the customer before a measurement profile. Each saved profile is a timestamped snapshot tied to that customer. An order references the selected customer and can reference one of that customer's measurement profiles. Order line items store a name/rate snapshot so changing a service's default price later does not rewrite old bills. Recording a payment appends a payment record and updates the order's paid amount in a transaction. Admin reports query those same database tables.

## Storage decision

PostgreSQL is the selected relational database in this version, with Flyway migrations. Supabase Storage is for public catalogue product imagery and the optional public GPay QR image only. The bucket must be configured intentionally as public for those public assets. Do not put measurement details, customer records, or private customer images in the public bucket.
