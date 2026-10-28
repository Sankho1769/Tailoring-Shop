package com.jollytailor.controller;

import com.jollytailor.service.StorageService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final StorageService storage;
    private final PasswordEncoder encoder;

    public AdminController(JdbcTemplate jdbc, StorageService storage, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.encoder = encoder;
    }

    @GetMapping("/stats")
    public Map<String,Object> stats(@RequestParam(defaultValue = "month") String range) {
        String period = switch (range) {
            case "today" -> "today";
            case "7d" -> "7d";
            case "month" -> "month";
            case "all" -> "all";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "range must be today, 7d, month or all");
        };

        String where = switch (period) {
            case "today" -> "created_at >= CURRENT_DATE";
            case "7d" -> "created_at >= NOW() - INTERVAL '7 days'";
            case "month" -> "created_at >= date_trunc('month', NOW())";
            default -> "TRUE";
        };

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("range", period);
        out.put("orders", jdbc.queryForObject("SELECT COUNT(*) FROM tailoring_orders WHERE " + where, Long.class));
        out.put("billed", zero(jdbc.queryForObject("SELECT COALESCE(SUM(total),0) FROM tailoring_orders WHERE " + where, BigDecimal.class)));
        out.put("collected", zero(jdbc.queryForObject("SELECT COALESCE(SUM(amount_paid),0) FROM tailoring_orders WHERE " + where, BigDecimal.class)));
        out.put("due", zero(jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(total-amount_paid,0)),0) FROM tailoring_orders WHERE " + where, BigDecimal.class)));
        out.put("customers", jdbc.queryForObject("SELECT COUNT(*) FROM customers", Long.class));
        out.put("products", jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE active=true", Long.class));
        out.put("requests", jdbc.queryForObject("SELECT COUNT(*) FROM customer_requests WHERE status='NEW'", Long.class));

        // Payment status counts and totals
        out.put("paidOrders", jdbc.queryForObject(
                "SELECT COUNT(*) FROM tailoring_orders WHERE " + where + " AND total > 0 AND amount_paid >= total", Long.class));
        out.put("partiallyPaidOrders", jdbc.queryForObject(
                "SELECT COUNT(*) FROM tailoring_orders WHERE " + where + " AND amount_paid > 0 AND amount_paid < total", Long.class));
        out.put("dueOrders", jdbc.queryForObject(
                "SELECT COUNT(*) FROM tailoring_orders WHERE " + where + " AND amount_paid = 0 AND total > 0", Long.class));

        List<Map<String,Object>> salesByDay = new ArrayList<>();
        LocalDate today = LocalDate.now();
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ofPattern("dd MMM");
        for (int i = 6; i >= 0; i--) {
            LocalDate d = today.minusDays(i);
            String dayLabel = d.format(fmt);
            BigDecimal dayTotal = jdbc.queryForObject(
                    "SELECT COALESCE(SUM(total),0) FROM tailoring_orders WHERE CAST(created_at AS DATE) = ?",
                    BigDecimal.class, java.sql.Date.valueOf(d));
            salesByDay.add(Map.of("day", dayLabel, "sales", zero(dayTotal)));
        }
        out.put("salesByDay", salesByDay);

        out.put("topServices", jdbc.queryForList(
                "SELECT item_name AS name, SUM(quantity) AS quantity, SUM(amount) AS revenue " +
                "FROM order_items i JOIN tailoring_orders o ON o.id=i.order_id " +
                "WHERE " + where.replace("created_at", "o.created_at") + " GROUP BY item_name ORDER BY revenue DESC LIMIT 8"));

        out.put("recentOrders", jdbc.queryForList(
                "SELECT o.id, o.order_number, o.status, " +
                "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                "o.total, o.amount_paid, (o.total-o.amount_paid) AS due, o.created_at, c.name AS customer, c.phone " +
                "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id ORDER BY o.created_at DESC LIMIT 10"));

        out.put("paymentMethods", jdbc.queryForList(
                "SELECT method, COALESCE(SUM(amount),0) AS amount, COUNT(*) AS transactions " +
                "FROM payments WHERE received_at >= CASE WHEN ?='today' THEN CURRENT_DATE WHEN ?='7d' THEN NOW()-INTERVAL '7 days' WHEN ?='month' THEN date_trunc('month',NOW()) ELSE '-infinity'::timestamptz END " +
                "GROUP BY method ORDER BY amount DESC", period, period, period));

        return out;
    }

    @GetMapping("/customers")
    public List<Map<String,Object>> customers(@RequestParam(required = false) String q) {
        String baseSql = "SELECT c.id, c.name, c.phone, c.address, c.notes, c.created_at, " +
                "COUNT(o.id) AS total_orders, " +
                "COALESCE(SUM(o.total), 0) AS total_spent, " +
                "COALESCE(SUM(GREATEST(o.total - o.amount_paid, 0)), 0) AS total_due, " +
                "MAX(o.created_at) AS last_order_date " +
                "FROM customers c " +
                "LEFT JOIN tailoring_orders o ON o.customer_id = c.id ";
        if (q == null || q.isBlank()) {
            return jdbc.queryForList(baseSql + "GROUP BY c.id ORDER BY total_orders DESC, c.name LIMIT 300");
        }
        String term = "%" + q.trim() + "%";
        return jdbc.queryForList(baseSql + "WHERE c.name ILIKE ? OR c.phone ILIKE ? GROUP BY c.id ORDER BY total_orders DESC, c.name LIMIT 100", term, term);
    }

    @GetMapping("/customers/{id}/orders")
    public List<Map<String,Object>> customerOrders(@PathVariable UUID id) {
        return jdbc.queryForList(
                "SELECT o.id, o.order_number, o.status, " +
                "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                "o.due_date, o.subtotal, o.discount, o.total, o.amount_paid, (o.total - o.amount_paid) AS due, o.created_at " +
                "FROM tailoring_orders o WHERE o.customer_id = ? ORDER BY o.created_at DESC", id);
    }

    @GetMapping("/payments")
    public List<Map<String,Object>> payments(@RequestParam(required = false) String q) {
        String sql = "SELECT p.id, p.order_id, p.amount, p.method, p.reference, p.note, " +
                "COALESCE(p.payment_type, 'PAYMENT') AS payment_type, p.received_at, " +
                "o.order_number, c.name AS customer, c.phone, u.username AS collector " +
                "FROM payments p " +
                "JOIN tailoring_orders o ON o.id = p.order_id " +
                "JOIN customers c ON c.id = o.customer_id " +
                "LEFT JOIN app_users u ON u.id = p.received_by ";
        if (q == null || q.isBlank()) {
            return jdbc.queryForList(sql + "ORDER BY p.received_at DESC LIMIT 300");
        }
        String term = "%" + q.trim() + "%";
        return jdbc.queryForList(sql + "WHERE o.order_number ILIKE ? OR c.name ILIKE ? OR c.phone ILIKE ? OR p.reference ILIKE ? ORDER BY p.received_at DESC LIMIT 100", term, term, term, term);
    }

    @GetMapping("/shop-settings")
    public Map<String,Object> shopSettings() {
        Map<String,Object> out = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM shop_settings", rs -> {
            out.put(rs.getString(1), rs.getString(2));
        });
        return out;
    }

    @PutMapping("/shop-settings")
    public Map<String,String> saveShopSettings(@RequestBody Map<String,Object> body) {
        for (String key : List.of("shop_name", "shop_address", "shop_phone", "whatsapp_number", "upi_id")) {
            if (body.containsKey(key)) {
                String value = body.get(key) == null ? "" : body.get(key).toString().trim();
                if (value.length() > 1000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting is too long");
                }
                jdbc.update("INSERT INTO shop_settings(setting_key, setting_value, updated_at) VALUES(?,?,NOW()) " +
                            "ON CONFLICT(setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value, updated_at=NOW()", key, value);
            }
        }
        return Map.of("message", "Shop settings saved");
    }

    @GetMapping("/categories")
    public List<Map<String,Object>> categories() {
        return jdbc.queryForList("SELECT id, name, description, active, created_at FROM categories ORDER BY name");
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createCategory(@RequestBody Map<String,Object> b) {
        String name = text(b, "name");
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category name required");
        }
        try {
            Long id = jdbc.queryForObject("INSERT INTO categories(name, description) VALUES(?,?) RETURNING id",
                    Long.class, name, blank(text(b, "description")));
            return Map.of("id", id, "name", name);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category already exists");
        }
    }

    @DeleteMapping("/categories/{id}")
    public Map<String,String> deleteCategory(@PathVariable long id) {
        jdbc.update("UPDATE categories SET active=false WHERE id=?", id);
        return Map.of("message", "Category deactivated");
    }

    @GetMapping("/products")
    public List<Map<String,Object>> products() {
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT p.id, p.name, p.description, p.default_rate, p.image_key, p.active, c.id AS category_id, c.name AS category " +
                "FROM products p LEFT JOIN categories c ON c.id=p.category_id ORDER BY p.name");
        rows.forEach(r -> {
            Object key = r.get("image_key");
            if (key != null && !key.toString().isBlank()) {
                r.put("imageUrl", storage.publicUrl(key.toString()));
            }
        });
        return rows;
    }

    @PostMapping("/products")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createProduct(@RequestBody Map<String,Object> b) {
        String name = text(b, "name");
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product name required");
        }
        BigDecimal rate = decimal(b.get("defaultRate"));
        if (rate.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rate cannot be negative");
        }
        Long cat = longOrNull(b.get("categoryId"));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO products(id, category_id, name, description, default_rate) VALUES(?,?,?,?,?)",
                    id, cat, name, blank(text(b, "description")), rate);
            return Map.of("id", id, "name", name, "defaultRate", rate);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product already exists");
        }
    }

    @PatchMapping("/products/{id}")
    public Map<String,String> updateProduct(@PathVariable UUID id, @RequestBody Map<String,Object> b) {
        if (b.containsKey("name")) jdbc.update("UPDATE products SET name=?, updated_at=NOW() WHERE id=?", text(b, "name"), id);
        if (b.containsKey("defaultRate")) jdbc.update("UPDATE products SET default_rate=?, updated_at=NOW() WHERE id=?", decimal(b.get("defaultRate")), id);
        if (b.containsKey("active")) jdbc.update("UPDATE products SET active=?, updated_at=NOW() WHERE id=?", Boolean.parseBoolean(String.valueOf(b.get("active"))), id);
        if (b.containsKey("description")) jdbc.update("UPDATE products SET description=?, updated_at=NOW() WHERE id=?", blank(text(b, "description")), id);
        if (b.containsKey("categoryId")) jdbc.update("UPDATE products SET category_id=?, updated_at=NOW() WHERE id=?", longOrNull(b.get("categoryId")), id);
        return Map.of("message", "Product updated");
    }

    @PostMapping("/products/{id}/image")
    public Map<String,Object> productImage(@PathVariable UUID id, @RequestParam("file") MultipartFile file) {
        String key = storage.uploadPublicCatalogImage(file);
        int n = jdbc.update("UPDATE products SET image_key=?, updated_at=NOW() WHERE id=?", key, id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        return Map.of("imageKey", key, "url", storage.publicUrl(key), "message", "Image uploaded successfully");
    }

    @GetMapping("/users")
    public List<Map<String,Object>> users() {
        return jdbc.queryForList("SELECT id, username, role, active, created_at FROM app_users ORDER BY id");
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createUser(@RequestBody Map<String,Object> b) {
        String username = text(b, "username");
        String password = text(b, "password");
        String role = text(b, "role").toUpperCase();
        if (username.isBlank() || password.length() < 12 || !Set.of("ADMIN", "TAILOR").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide username, password of at least 12 characters, and ADMIN or TAILOR role");
        }
        try {
            Long id = jdbc.queryForObject("INSERT INTO app_users(username, password_hash, role) VALUES(?,?,?) RETURNING id",
                    Long.class, username, encoder.encode(password), role);
            return Map.of("id", id, "username", username, "role", role);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
    }

    @PatchMapping("/users/{id}/active")
    public Map<String,String> activeUser(@PathVariable long id, @RequestBody Map<String,Object> b) {
        jdbc.update("UPDATE app_users SET active=? WHERE id=?", Boolean.parseBoolean(String.valueOf(b.get("active"))), id);
        return Map.of("message", "User status updated");
    }

    @PatchMapping("/users/{id}/password")
    public Map<String,String> resetUserPassword(@PathVariable long id, @RequestBody Map<String,Object> b) {
        String password = text(b, "password");
        if (password.length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 12 characters");
        }
        int n = jdbc.update("UPDATE app_users SET password_hash=? WHERE id=?", encoder.encode(password), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        return Map.of("message", "Password reset successfully");
    }

    @PatchMapping("/users/{id}/role")
    public Map<String,String> updateUserRole(@PathVariable long id, @RequestBody Map<String,Object> b) {
        String role = text(b, "role").toUpperCase();
        if (!Set.of("ADMIN", "TAILOR").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be ADMIN or TAILOR");
        }
        int n = jdbc.update("UPDATE app_users SET role=? WHERE id=?", role, id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        return Map.of("message", "Role updated to " + role);
    }

    // CSV Reports
    @GetMapping(value = "/reports/orders-csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportOrdersCsv() {
        List<Map<String,Object>> orders = jdbc.queryForList(
                "SELECT o.order_number, c.name AS customer_name, c.phone, o.status, " +
                "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                "o.subtotal, o.discount, o.total, o.amount_paid, (o.total - o.amount_paid) AS due, o.due_date, o.created_at " +
                "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id ORDER BY o.created_at DESC");

        StringBuilder sb = new StringBuilder();
        sb.append("Bill Number,Customer Name,Phone,Order Status,Payment Status,Subtotal,Discount,Total,Paid,Due,Due Date,Created At\r\n");
        for (Map<String,Object> row : orders) {
            sb.append(escapeCsv(row.get("order_number"))).append(",")
              .append(escapeCsv(row.get("customer_name"))).append(",")
              .append(escapeCsv(row.get("phone"))).append(",")
              .append(escapeCsv(row.get("status"))).append(",")
              .append(escapeCsv(row.get("payment_status"))).append(",")
              .append(row.get("subtotal")).append(",")
              .append(row.get("discount")).append(",")
              .append(row.get("total")).append(",")
              .append(row.get("amount_paid")).append(",")
              .append(row.get("due")).append(",")
              .append(escapeCsv(row.get("due_date"))).append(",")
              .append(escapeCsv(row.get("created_at"))).append("\r\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"orders-" + LocalDate.now() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    @GetMapping(value = "/reports/payments-csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportPaymentsCsv() {
        List<Map<String,Object>> payments = jdbc.queryForList(
                "SELECT p.id, o.order_number, c.name AS customer, p.amount, p.method, p.reference, p.note, " +
                "COALESCE(p.payment_type, 'PAYMENT') AS payment_type, p.received_at, u.username AS collector " +
                "FROM payments p JOIN tailoring_orders o ON o.id=p.order_id JOIN customers c ON c.id=o.customer_id " +
                "LEFT JOIN app_users u ON u.id=p.received_by ORDER BY p.received_at DESC");

        StringBuilder sb = new StringBuilder();
        sb.append("Payment ID,Bill Number,Customer,Amount,Method,Reference,Note,Type,Received At,Collector\r\n");
        for (Map<String,Object> row : payments) {
            sb.append(escapeCsv(row.get("id"))).append(",")
              .append(escapeCsv(row.get("order_number"))).append(",")
              .append(escapeCsv(row.get("customer"))).append(",")
              .append(row.get("amount")).append(",")
              .append(escapeCsv(row.get("method"))).append(",")
              .append(escapeCsv(row.get("reference"))).append(",")
              .append(escapeCsv(row.get("note"))).append(",")
              .append(escapeCsv(row.get("payment_type"))).append(",")
              .append(escapeCsv(row.get("received_at"))).append(",")
              .append(escapeCsv(row.get("collector"))).append("\r\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"payments-" + LocalDate.now() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    @GetMapping(value = "/reports/customers-csv", produces = "text/csv")
    public ResponseEntity<byte[]> exportCustomersCsv() {
        List<Map<String,Object>> customers = jdbc.queryForList(
                "SELECT c.name, c.phone, c.address, c.notes, COUNT(o.id) AS total_orders, " +
                "COALESCE(SUM(o.total), 0) AS total_spent, COALESCE(SUM(GREATEST(o.total - o.amount_paid, 0)), 0) AS total_due, " +
                "c.created_at FROM customers c LEFT JOIN tailoring_orders o ON o.customer_id=c.id " +
                "GROUP BY c.id ORDER BY total_orders DESC, c.name");

        StringBuilder sb = new StringBuilder();
        sb.append("Customer Name,Phone,Address,Notes,Total Orders,Total Spent,Total Due,Registered At\r\n");
        for (Map<String,Object> row : customers) {
            sb.append(escapeCsv(row.get("name"))).append(",")
              .append(escapeCsv(row.get("phone"))).append(",")
              .append(escapeCsv(row.get("address"))).append(",")
              .append(escapeCsv(row.get("notes"))).append(",")
              .append(row.get("total_orders")).append(",")
              .append(row.get("total_spent")).append(",")
              .append(row.get("total_due")).append(",")
              .append(escapeCsv(row.get("created_at"))).append("\r\n");
        }
        byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"customers-" + LocalDate.now() + ".csv\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(bytes);
    }

    private String escapeCsv(Object o) {
        if (o == null) return "\"\"";
        return "\"" + String.valueOf(o).replace("\"", "\"\"") + "\"";
    }

    private String text(Map<String,Object> b, String k) {
        Object v = b.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private String blank(String s) {
        return s.isBlank() ? null : s;
    }

    private BigDecimal decimal(Object o) {
        try {
            return new BigDecimal(o == null ? "0" : o.toString());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid numeric value");
        }
    }

    private Long longOrNull(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return Long.valueOf(o.toString());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid category ID");
        }
    }

    private BigDecimal zero(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }
}
