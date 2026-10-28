# Jolly Ladies Tailor — Connected Web Application (Java + PostgreSQL)

This is a connected starter implementation organized into three user-facing areas. Unlike the earlier single-file prototype, it uses a Java Spring Boot backend and a shared PostgreSQL database. The three pages are served by the same backend origin, avoiding unnecessary CORS setup.

## The three parts

1. **Consumer Website** (`/`) — public services catalogue and appointment/enquiry form. Enquiries are saved to PostgreSQL for the admin to manage.
2. **Tailor Workspace** (`/tailor.html`) — staff login, customer records, customer-specific measurement snapshots, order/bill creation, delivery status, WhatsApp bill sharing, and manual recording of verified payments.
3. **Admin Console** (`/admin.html`) — role-restricted admin login, billing and collected-payment statistics, outstanding balances, sales by day, service/product and category management, staff accounts, consumer enquiries, CSV export, shop profile settings, GPay/UPI QR display and Supabase catalog-image upload.

## Technology

- Java 21 + Spring Boot 3.5.x REST backend
- PostgreSQL 16 for customers, measurements, products, categories, orders, order items, payments and enquiries
- Spring Security session authentication with BCrypt password hashing, role checks and CSRF cookie support
- Flyway-managed SQL schema migration
- Supabase Storage S3-compatible endpoint for public product/catalog images (credentials only on the backend)
- HTML/CSS/JavaScript frontends served from Spring Boot; orchid pink `#E7B0DE`, eggplant violet `#400849`, signal orange `#FF5A0A`

**Database choice:** This starter selects PostgreSQL. Supabase itself is built around PostgreSQL, but this app connects directly to PostgreSQL for its relational records and uses Supabase Storage only for images. The database layer can be adapted to MySQL later, but this version's schema and SQL are PostgreSQL-specific.

## Run locally with Docker

Prerequisites: Docker Desktop / Docker Engine with Compose, and internet access for the first image/dependency build.

1. Copy `.env.example` to `.env`.
2. Replace all placeholder database and bootstrap passwords with strong values (at least 12 characters for the app passwords).
3. From this folder, run `docker compose up --build`.
4. Open `http://localhost:8080/` for the consumer site, `http://localhost:8080/tailor.html` for the tailor workspace, and `http://localhost:8080/admin.html` for admin.
5. Sign in using the bootstrap usernames/passwords from `.env`.

The bootstrap accounts are only inserted when those usernames don't exist yet. Changing bootstrap variables later does not reset existing account passwords; use the admin user controls or a controlled database password-reset procedure.

## Supabase Storage setup

1. Create a Supabase project and a Storage bucket named `tailor-catalog` (or set `SUPABASE_PUBLIC_BUCKET`).
2. Use a **public bucket for public catalogue/product imagery only**. Never store private body measurements, customer personal documents, or private customer photos in a public bucket.
3. In Supabase Storage S3 settings, create/use S3 access credentials. Add the endpoint in this shape to `.env`: `https://PROJECT_REF.storage.supabase.co/storage/v1/s3`; set the S3 access key, secret, and project URL `https://PROJECT_REF.supabase.co`.
4. Restart with `docker compose up --build`. Admin can then upload images against a product row.
5. Keep Supabase credentials secret; never add them to frontend JavaScript or commit `.env`.

You can leave Supabase fields blank if image uploads are not needed yet. The app will return a clear error from the image upload endpoint while the rest of the application works.

## WhatsApp and GPay

- WhatsApp sharing is **click-to-chat**: the tailor opens WhatsApp with a prepared bill summary and presses Send. It is not automatic API delivery.
- UPI payments are manually entered after the tailor verifies that money actually arrived in GPay. This starter does not claim to verify payment automatically or process funds.
- A normal QR image does not send payment confirmation to the website. GPay QR/site settings and displaying the QR on a customer invoice can be added as the next implementation step; keep QR assets public only when intentionally meant for customers to scan.

## API map

- `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout`, `GET /api/auth/csrf`
- Public: `GET /api/public/products`, `GET /api/public/categories`, `POST /api/public/booking-requests`
- Tailor/Admin: `/api/tailor/customers`, `/api/tailor/customers/{id}/measurements`, `/api/tailor/orders`, `/api/tailor/orders/{id}/payments`, `/api/tailor/orders/{id}/status`
- Admin only: `/api/admin/stats`, `/api/admin/shop-settings`, `/api/admin/products`, `/api/admin/categories`, `/api/admin/users`, `/api/admin/requests`, `/api/admin/media/products/{id}`, `/api/admin/media/shop/gpay-qr`

## Status and testing

This is a functional starter codebase, not a fully production-audited business system. The current execution environment did not have Maven installed and could not reach Maven Central, so a full Spring Boot compile/integration test could not be run here. JavaScript syntax and basic HTML structure checks passed. Before using real shop data, build and test locally, configure HTTPS in deployment, configure database backups and retention, review admin account lifecycle, add automated tests, test concurrency/numbering, and confirm that all hosting/platform policies meet the shop's needs. The first run needs Maven dependency downloads inside Docker.

## Privacy and backup

Measurements are personal information. Restrict access to authorized tailor/admin accounts, use HTTPS for a live deployment, protect secrets, make regular encrypted database backups and test restoring them. Do not commit actual customer data, production passwords or Supabase keys.
