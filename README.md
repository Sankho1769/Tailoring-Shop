# Jolly Ladies Tailor — Connected Web Application (Java 21 + Spring Boot + PostgreSQL)

A fully connected tailor shop management system and public consumer boutique for **Jolly Ladies Tailor**. The application connects the public landing page, the tailor workspace, and the admin console into a unified Spring Boot application backed by PostgreSQL.

---

## The Three Application Areas

### 1. Consumer Website (`/` or `/index.html`)
- **Branding & Visual Palette**: Orchid Pink (`#E7B0DE`), Deep Eggplant Violet (`#400849`), and Signal Orange (`#FF5A0A`).
- **Showcase**: Full services range (Blouse, Salwar / Suit, Lehenga, Pant, Frock, Peko, False Par, Precision Alterations).
- **Style Inspirations Guide**: Popular patterns and bridal blouse inspirations linked directly to the appointment form.
- **Location & Shop Details**: Shop hours, address, direct phone link, and instant WhatsApp chat (`https://wa.me/...`).
- **GPay / UPI Payment QR Code**: Displayed with clear customer verification instructions.
- **Appointment & Enquiry Form**: Submits requests directly to PostgreSQL for tailor/admin follow-up.
- **Security Boundary**: No public exposure to customer measurements, bills, or internal records.

### 2. Tailor Workspace (`/tailor.html`)
- **Step 1 — Customer Search & Registration**: Search by name or phone, register new customers, and update contact details in PostgreSQL.
- **Step 2 — Measurement Snapshots**: Measurement form is locked until a customer is chosen. Garment-specific measurement inputs (Inches or Centimetres), fitting preferences (Regular, Fitted, Comfort, Custom), and notes. Preserves full chronological measurement history.
- **Step 3 — Order & Billing**:
  - Add multiple stitching items with live subtotal calculation.
  - Apply discounts, promised delivery dates, and fabric notes.
  - Record initial advance payment and payment method (UPI, Cash, Card) directly on bill creation.
  - Generates unique bill numbers: `JLT-YYYYMMDD-XXXXX`.
  - Immediate printable invoice layout with shop header, itemization, customer details, balance due, and UPI QR code.
  - **WhatsApp e-Bill Sharing**: Formats a clean itemized summary and opens click-to-chat via `https://wa.me/<phone>?text=...`, along with a quick clipboard copy button.
- **Order Lifecycle Tracking**: Seamlessly advance orders through:
  `New` ➔ `In Progress` ➔ `Trial` ➔ `Ready` ➔ `Delivered` (or `Cancelled`).
- **Payment & Refund Tracking**:
  - Distinguishes **Payment Due**, **Partially Paid**, **Paid**, and **Refunded**.
  - Prevents overpayment beyond outstanding balance.
  - Record verified UPI / Cash / Card receipts or customer refunds with audit logging.

### 3. Admin Console (`/admin.html`)
- **Role-Gated Security**: Strictly restricted to accounts with role `ADMIN`.
- **Live Business Analytics**:
  - Total sales billed, collections received, outstanding balances, order counts, customer counts, active services, and new website enquiries.
  - Filterable by `Today`, `Last 7 days`, `This month`, and `All time`.
  - 7-day daily sales trend bar chart.
  - Top 8 revenue-generating services breakdown.
  - Collections categorized by payment method (UPI, Cash, Card, Bank Transfer).
- **Customer Directory & Order History**: Search customers, view total lifetime spend, total outstanding due, and drill into all past orders for any customer.
- **Payment Ledger & Audit Log**: Itemized transaction log of every payment and refund with date, customer, collector, payment method, and transaction reference.
- **Service & Category Catalog Management**: Add services, update default stitching rates, activate/deactivate services, and upload catalog photos.
- **Supabase Storage Integration**: Upload product catalog images and the public GPay QR code securely through backend S3 credentials.
- **Staff User Accounts**: Create accounts with role `TAILOR` or `ADMIN`, toggle active status, change roles, and reset passwords (enforcing minimum 12 characters).
- **One-Click CSV Exports**:
  - Summary KPI report CSV.
  - All orders CSV (`/api/admin/reports/orders-csv`).
  - All payments ledger CSV (`/api/admin/reports/payments-csv`).
  - All registered customers CSV (`/api/admin/reports/customers-csv`).
- **PostgreSQL Backup & Recovery Documentation**: Ready-to-run `pg_dump` and `pg_restore` commands.

---

## Technical Architecture

- **Runtime**: Java 21 (tested on OpenJDK 26 with `release 21`).
- **Framework**: Spring Boot 3.x with Spring Web, Spring JDBC, Spring Security, and Spring Validation.
- **Database**: PostgreSQL 16 (with Flyway database migrations `V1` and `V2`).
- **Image Storage**: Supabase Storage via AWS S3 SDK (credentials never exposed in frontend code).
- **Frontend**: Vanilla JavaScript (ES6+), responsive CSS, and semantic HTML5 served directly by Spring Boot (no CORS issues).
- **Testing**: 14 automated tests covering Context loading, Customer & Measurement persistence, Order calculation & lifecycle, Payment & refund rules, Security role boundaries, and Admin reporting.

---

## Getting Started

### 1. Prerequisites
- Java 21+ SDK
- Maven 3.9+
- Docker Desktop (or local PostgreSQL 16)

### 2. Environment Configuration
Copy `.env.example` to `.env` and configure your credentials:
```bash
cp .env.example .env
```
Ensure database passwords and bootstrap admin/tailor passwords are set (at least 12 characters).

### 3. Run with Docker Compose
```bash
docker compose up --build
```

### 4. Run Locally without Docker
To run with a local PostgreSQL database:
```bash
mvn spring-boot:run
```

To run the complete automated test suite (uses embedded in-memory H2 in PostgreSQL compatibility mode):
```bash
mvn test
```

### 5. Accessing the Application
- **Consumer Website**: `http://localhost:8080/`
- **Tailor Workspace**: `http://localhost:8080/tailor.html`
- **Admin Console**: `http://localhost:8080/admin.html`

Sign in with your configured bootstrap credentials (e.g. `admin` / `tailor`).

---

## Database Backup and Recovery

### Backup PostgreSQL Database
```bash
pg_dump -h localhost -p 5432 -U jolly_app -d jolly_tailor -F c -b -v -f "jolly_tailor_backup.dump"
```

### Restore PostgreSQL Database
```bash
pg_restore -h localhost -p 5432 -U jolly_app -d jolly_tailor -v -c "jolly_tailor_backup.dump"
```

### Backup via Docker
```bash
docker compose exec -T postgres pg_dump -U jolly_app -d jolly_tailor > jolly_tailor_backup.sql
```

---

## API Summary

- **Authentication**: `POST /api/auth/login`, `GET /api/auth/me`, `POST /api/auth/logout`, `GET /api/auth/csrf`
- **Public**:
  - `GET /api/public/shop`, `GET /api/public/categories`, `GET /api/public/products`
  - `POST /api/public/booking-requests`
- **Tailor Workspace (TAILOR or ADMIN)**:
  - `GET/POST /api/tailor/customers`, `GET/PATCH /api/tailor/customers/{id}`
  - `GET/POST /api/tailor/customers/{id}/measurements`
  - `GET/POST /api/tailor/orders`, `GET /api/tailor/orders/{id}`
  - `PATCH /api/tailor/orders/{id}/status`
  - `POST /api/tailor/orders/{id}/payments`, `POST /api/tailor/orders/{id}/refunds`
- **Admin Console (ADMIN only)**:
  - `GET /api/admin/stats`, `GET/PUT /api/admin/shop-settings`
  - `GET /api/admin/customers`, `GET /api/admin/customers/{id}/orders`
  - `GET /api/admin/payments`
  - `GET /api/admin/reports/orders-csv`, `GET /api/admin/reports/payments-csv`, `GET /api/admin/reports/customers-csv`
  - `GET/POST /api/admin/categories`, `DELETE /api/admin/categories/{id}`
  - `GET/POST /api/admin/products`, `PATCH /api/admin/products/{id}`, `POST /api/admin/products/{id}/image`
  - `POST /api/admin/media/shop/gpay-qr`, `POST /api/admin/media/products/{id}`
  - `GET/POST /api/admin/users`, `PATCH /api/admin/users/{id}/active`, `PATCH /api/admin/users/{id}/password`, `PATCH /api/admin/users/{id}/role`
  - `GET /api/admin/requests`, `PATCH /api/admin/requests/{id}/status`
