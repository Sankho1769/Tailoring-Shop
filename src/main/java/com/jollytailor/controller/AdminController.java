package com.jollytailor.controller;

import com.jollytailor.service.ExcelExportService;
import com.jollytailor.service.InvoicePdfService;
import com.jollytailor.service.StorageService;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc;
    private final StorageService storage;
    private final PasswordEncoder encoder;
    private final InvoicePdfService pdfService;
    private final ExcelExportService excelService;
    private final TailorController tailorController;

    public AdminController(JdbcTemplate jdbc, StorageService storage, PasswordEncoder encoder,
                           InvoicePdfService pdfService, ExcelExportService excelService,
                           TailorController tailorController) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.encoder = encoder;
        this.pdfService = pdfService;
        this.excelService = excelService;
        this.tailorController = tailorController;
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

        OffsetDateTime cutoff = switch (period) {
            case "today" -> LocalDate.now().atStartOfDay().atOffset(ZoneOffset.UTC);
            case "7d" -> OffsetDateTime.now(ZoneOffset.UTC).minusDays(7);
            case "month" -> LocalDate.now().withDayOfMonth(1).atStartOfDay().atOffset(ZoneOffset.UTC);
            default -> null;
        };

        String where = cutoff != null ? "created_at >= ?" : "1=1";
        Object[] params = cutoff != null ? new Object[]{cutoff} : new Object[]{};

        Map<String,Object> out = new LinkedHashMap<>();
        out.put("range", period);
        out.put("orders", jdbc.queryForObject("SELECT COUNT(*) FROM tailoring_orders WHERE " + where, Long.class, params));
        out.put("billed", zero(jdbc.queryForObject("SELECT COALESCE(SUM(total),0) FROM tailoring_orders WHERE " + where, BigDecimal.class, params)));
        out.put("collected", zero(jdbc.queryForObject("SELECT COALESCE(SUM(amount_paid),0) FROM tailoring_orders WHERE " + where, BigDecimal.class, params)));
        out.put("due", zero(jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(total-amount_paid,0)),0) FROM tailoring_orders WHERE " + where, BigDecimal.class, params)));
        out.put("customers", jdbc.queryForObject("SELECT COUNT(*) FROM customers", Long.class));
        out.put("products", jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE active=true", Long.class));
        out.put("requests", jdbc.queryForObject("SELECT COUNT(*) FROM customer_requests WHERE status='NEW'", Long.class));

        // Payment status counts and totals
        out.put("paidOrders", jdbc.queryForObject(
                "SELECT COUNT(*) FROM tailoring_orders WHERE " + where + " AND total > 0 AND amount_paid >= total", Long.class, params));
        out.put("partiallyPaidOrders", jdbc.queryForObject(
                "SELECT COUNT(*) FROM tailoring_orders WHERE " + where + " AND amount_paid > 0 AND amount_paid < total", Long.class, params));
        out.put("dueOrders", jdbc.queryForObject(
                "SELECT COUNT(*) FROM tailoring_orders WHERE " + where + " AND amount_paid = 0 AND total > 0", Long.class, params));

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

        String tsWhere = cutoff != null ? "o.created_at >= ?" : "1=1";
        String tsSql = "SELECT item_name AS name, SUM(quantity) AS quantity, SUM(amount) AS revenue " +
                "FROM order_items i JOIN tailoring_orders o ON o.id=i.order_id " +
                "WHERE " + tsWhere + " GROUP BY item_name ORDER BY revenue DESC LIMIT 8";
        out.put("topServices", cutoff != null ? jdbc.queryForList(tsSql, cutoff) : jdbc.queryForList(tsSql));

        out.put("recentOrders", jdbc.queryForList(
                "SELECT o.id, o.order_number, o.status, " +
                "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                "o.total, o.amount_paid, (o.total-o.amount_paid) AS due, o.created_at, c.name AS customer, c.phone " +
                "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id ORDER BY o.created_at DESC LIMIT 10"));

        String pmSql = cutoff != null
                ? "SELECT method, COALESCE(SUM(amount),0) AS amount, COUNT(*) AS transactions FROM payments WHERE received_at >= ? GROUP BY method ORDER BY amount DESC"
                : "SELECT method, COALESCE(SUM(amount),0) AS amount, COUNT(*) AS transactions FROM payments GROUP BY method ORDER BY amount DESC";
        out.put("paymentMethods", cutoff != null ? jdbc.queryForList(pmSql, cutoff) : jdbc.queryForList(pmSql));

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

    @PostMapping("/customers")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createCustomer(@RequestBody Map<String,Object> b) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.createCustomer(b);
    }

    @RequestMapping(value = "/customers/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    public Map<String,Object> updateCustomer(@PathVariable UUID id, @RequestBody Map<String,Object> b) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.updateCustomer(id, b);
    }

    @GetMapping("/customers/{id}")
    public Map<String,Object> customer(@PathVariable UUID id) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.customer(id);
    }

    @GetMapping("/customers/{id}/measurements")
    public List<Map<String,Object>> measurements(@PathVariable UUID id) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.measurements(id);
    }

    @PostMapping("/customers/{id}/measurements")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createMeasurement(@PathVariable UUID id, @RequestBody Map<String,Object> b, Authentication auth) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.addMeasurement(id, b, auth);
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
        for (String key : List.of("shop_name", "shop_address", "shop_phone", "whatsapp_number", "admin_notification_phone", "upi_id", "public_base_url")) {
            if (body.containsKey(key)) {
                String value = body.get(key) == null ? "" : body.get(key).toString().trim();
                if (value.length() > 1000) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting is too long");
                }
                int updated = jdbc.update("UPDATE shop_settings SET setting_value=?, updated_at=NOW() WHERE setting_key=?", value, key);
                if (updated == 0) {
                    jdbc.update("INSERT INTO shop_settings(setting_key, setting_value, updated_at) VALUES(?,?,NOW())", key, value);
                }
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
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO categories(name, description) VALUES(?,?)", new String[]{"id"});
                ps.setString(1, name);
                ps.setString(2, blank(text(b, "description")));
                return ps;
            }, keyHolder);
            Long id;
            try {
                id = extractKey(keyHolder);
                if (id == null || id == 1L) {
                    id = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, name);
                }
            } catch (Exception ex) {
                id = jdbc.queryForObject("SELECT id FROM categories WHERE name=?", Long.class, name);
            }
            return Map.of("id", id, "name", name);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Category already exists");
        }
    }

    @DeleteMapping("/categories/{id}")
    public Map<String,String> deleteCategory(@PathVariable long id) {
        Integer activeProductCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM products WHERE category_id = ? AND active = true", Integer.class, id);
        if (activeProductCount != null && activeProductCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot deactivate category while " + activeProductCount + " active product(s) reference it. Please reassign products first.");
        }
        jdbc.update("UPDATE categories SET active=false WHERE id=?", id);
        return Map.of("message", "Category deactivated");
    }

    @GetMapping("/products")
    public List<Map<String,Object>> products() {
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT p.id, p.name, p.description, p.default_rate, p.product_type, p.stock_quantity, p.image_key, p.active, c.id AS category_id, c.name AS category " +
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
        String productType = text(b, "productType").toUpperCase();
        if (productType.isBlank() || !Set.of("SERVICE", "READY_MADE").contains(productType)) {
            productType = "SERVICE";
        }
        int stockQuantity = 0;
        if (b.containsKey("stockQuantity")) {
            try {
                stockQuantity = Integer.parseInt(String.valueOf(b.get("stockQuantity")));
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid stock quantity");
            }
            if (stockQuantity < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock quantity cannot be negative");
            }
        }
        Long cat = longOrNull(b.get("categoryId"));
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO products(id, category_id, name, description, default_rate, product_type, stock_quantity) VALUES(?,?,?,?,?,?,?)",
                    id, cat, name, blank(text(b, "description")), rate, productType, stockQuantity);
            return Map.of("id", id, "name", name, "defaultRate", rate, "productType", productType, "stockQuantity", stockQuantity);
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product already exists");
        }
    }

    @PatchMapping("/products/{id}")
    public Map<String,String> updateProduct(@PathVariable UUID id, @RequestBody Map<String,Object> b) {
        if (b.containsKey("name")) jdbc.update("UPDATE products SET name=?, updated_at=NOW() WHERE id=?", text(b, "name"), id);
        if (b.containsKey("defaultRate")) {
            BigDecimal rate = decimal(b.get("defaultRate"));
            if (rate.signum() < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Rate cannot be negative");
            jdbc.update("UPDATE products SET default_rate=?, updated_at=NOW() WHERE id=?", rate, id);
        }
        if (b.containsKey("productType")) {
            String pt = text(b, "productType").toUpperCase();
            if (!Set.of("SERVICE", "READY_MADE").contains(pt)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid product type");
            jdbc.update("UPDATE products SET product_type=?, updated_at=NOW() WHERE id=?", pt, id);
        }
        if (b.containsKey("stockQuantity")) {
            int sq = Integer.parseInt(String.valueOf(b.get("stockQuantity")));
            if (sq < 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Stock quantity cannot be negative");
            jdbc.update("UPDATE products SET stock_quantity=?, updated_at=NOW() WHERE id=?", sq, id);
        }
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

    @GetMapping("/price-adjustments")
    public List<Map<String,Object>> priceAdjustments() {
        return jdbc.queryForList(
                "SELECT oi.id, oi.order_id, oi.item_name, oi.quantity, oi.default_rate, oi.unit_rate, oi.discount, oi.amount, " +
                "oi.adjustment_reason, oi.adjusted_at, (oi.unit_rate - oi.default_rate) AS difference, " +
                "o.order_number, c.name AS customer_name, c.phone AS customer_phone, " +
                "u.username AS adjusted_by_name " +
                "FROM order_items oi " +
                "JOIN tailoring_orders o ON o.id = oi.order_id " +
                "JOIN customers c ON c.id = o.customer_id " +
                "LEFT JOIN app_users u ON u.id = oi.adjusted_by " +
                "WHERE oi.adjusted_at IS NOT NULL OR oi.default_rate <> oi.unit_rate " +
                "ORDER BY oi.adjusted_at DESC, o.created_at DESC");
    }

    @GetMapping("/orders/{id}")
    public Map<String,Object> order(@PathVariable UUID id) {
        Map<String,Object> out;
        try {
            out = jdbc.queryForMap(
                    "SELECT o.id, o.order_number, o.customer_id, o.measurement_id, o.status, " +
                    "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                    "o.due_date, o.notes, o.subtotal, o.discount, o.total, o.amount_paid, (o.total - o.amount_paid) AS due, o.created_at, " +
                    "c.name AS customer, c.phone, c.address " +
                    "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id WHERE o.id=?", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        out.put("items", jdbc.queryForList(
                "SELECT oi.id, oi.product_id, oi.item_name, oi.quantity, oi.default_rate, oi.unit_rate, oi.discount, oi.amount, " +
                "oi.adjustment_reason, oi.adjusted_at, u.username AS adjusted_by_name " +
                "FROM order_items oi LEFT JOIN app_users u ON u.id = oi.adjusted_by WHERE oi.order_id=?", id));
        out.put("payments", jdbc.queryForList(
                "SELECT p.id, p.amount, p.method, p.reference, p.note, COALESCE(p.payment_type, 'PAYMENT') AS payment_type, p.received_at, u.username AS collector " +
                "FROM payments p LEFT JOIN app_users u ON u.id = p.received_by WHERE p.order_id=? ORDER BY p.received_at", id));
        return out;
    }

    @GetMapping("/orders/{id}/measurement")
    public Map<String,Object> getOrderMeasurement(@PathVariable UUID id) {
        Map<String,Object> orderRow;
        try {
            orderRow = jdbc.queryForMap(
                "SELECT o.order_number, o.measurement_id, c.name AS customer_name " +
                "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id WHERE o.id=?", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        List<Map<String,Object>> mRows = new ArrayList<>();
        try {
            String mIdsRaw = jdbc.queryForObject("SELECT measurement_ids FROM tailoring_orders WHERE id=?", String.class, id);
            if (mIdsRaw != null && !mIdsRaw.isBlank()) {
                for (String part : mIdsRaw.split(",")) {
                    if (!part.trim().isBlank()) {
                        try {
                            UUID mid = UUID.fromString(part.trim());
                            Map<String,Object> mRow = jdbc.queryForMap(
                                "SELECT m.id, m.customer_id, m.garment_type, m.unit, CAST(m.dimensions AS VARCHAR) AS dimensions, " +
                                "m.fit_preference, m.notes, m.created_at, u.username AS recorded_by " +
                                "FROM measurements m LEFT JOIN app_users u ON u.id = m.created_by WHERE m.id=?", mid);
                            mRows.add(mRow);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

        if (mRows.isEmpty()) {
            Object measurementId = orderRow.get("measurement_id");
            if (measurementId != null) {
                try {
                    Map<String,Object> mRow = jdbc.queryForMap(
                        "SELECT m.id, m.customer_id, m.garment_type, m.unit, CAST(m.dimensions AS VARCHAR) AS dimensions, " +
                        "m.fit_preference, m.notes, m.created_at, u.username AS recorded_by " +
                        "FROM measurements m LEFT JOIN app_users u ON u.id = m.created_by WHERE m.id=?", measurementId);
                    mRows.add(mRow);
                } catch (Exception ignored) {}
            }
        }

        if (mRows.isEmpty()) {
            return Map.of(
                "hasMeasurement", false,
                "orderNumber", orderRow.get("order_number"),
                "customerName", orderRow.get("customer_name"),
                "message", "No measurement snapshot was attached to this order."
            );
        }

        Map<String,Object> out = new HashMap<>();
        out.put("hasMeasurement", true);
        out.put("orderNumber", orderRow.get("order_number"));
        out.put("customerName", orderRow.get("customer_name"));
        out.put("measurement", mRows.get(0));
        out.put("measurements", mRows);
        return out;
    }

    @GetMapping("/customers/{id}/measurements/{mid}")
    public Map<String,Object> measurement(@PathVariable UUID id, @PathVariable UUID mid) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.measurement(id, mid);
    }

    @RequestMapping(value = {"/customers/{id}/measurements/{mid}", "/measurements/{mid}"}, method = {RequestMethod.PUT, RequestMethod.PATCH})
    public Map<String,Object> updateMeasurement(@PathVariable(required = false) UUID id, @PathVariable UUID mid, @RequestBody Map<String,Object> b, Authentication auth) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.updateMeasurement(id, mid, b, auth);
    }

    @GetMapping("/orders")
    public List<Map<String,Object>> orders(@RequestParam(required = false) String q) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.orders(q);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createOrder(@RequestBody Map<String,Object> b, Authentication auth) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.createOrder(b, auth);
    }

    @PatchMapping("/orders/{id}")
    public Map<String,Object> updateOrder(@PathVariable UUID id, @RequestBody Map<String,Object> b, Authentication auth) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.updateOrder(id, b, auth);
    }

    @PatchMapping("/orders/{id}/status")
    public Map<String,Object> updateOrderStatus(@PathVariable UUID id, @RequestBody Map<String,Object> b) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.updateStatus(id, b);
    }

    @PostMapping("/orders/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> recordPayment(@PathVariable UUID id, @RequestBody Map<String,Object> b, Authentication auth) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.recordPayment(id, b, auth);
    }

    @PostMapping("/orders/{id}/refunds")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> recordRefund(@PathVariable UUID id, @RequestBody Map<String,Object> b, Authentication auth) {
        if (tailorController == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return tailorController.recordRefund(id, b, auth);
    }

    @GetMapping({"/users", "/staff"})
    public List<Map<String,Object>> staffList() {
        return jdbc.queryForList(
                "SELECT u.id, u.username, u.full_name, u.role, u.active, u.created_at, u.last_login_at, " +
                "COALESCE(w.total_earned, 0) AS total_earned, " +
                "COALESCE(p.total_paid, 0) AS total_paid, " +
                "(COALESCE(w.total_earned, 0) - COALESCE(p.total_paid, 0)) AS remaining_balance " +
                "FROM app_users u " +
                "LEFT JOIN (SELECT staff_id, SUM(amount_owed) AS total_earned FROM staff_work GROUP BY staff_id) w ON w.staff_id = u.id " +
                "LEFT JOIN (SELECT staff_id, SUM(amount) AS total_paid FROM staff_payments GROUP BY staff_id) p ON p.staff_id = u.id " +
                "ORDER BY u.id");
    }

    @PostConstruct
    public void initStaffWorkSchema() {
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS staff_work (" +
                    "id UUID PRIMARY KEY, " +
                    "staff_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE, " +
                    "order_id UUID REFERENCES tailoring_orders(id) ON DELETE SET NULL, " +
                    "work_date DATE NOT NULL, " +
                    "description VARCHAR(255) NOT NULL, " +
                    "amount_owed NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (amount_owed >= 0), " +
                    "notes TEXT, " +
                    "recorded_by BIGINT REFERENCES app_users(id), " +
                    "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            jdbc.execute("CREATE TABLE IF NOT EXISTS staff_payments (" +
                    "id UUID PRIMARY KEY, " +
                    "staff_id BIGINT NOT NULL REFERENCES app_users(id) ON DELETE CASCADE, " +
                    "work_id UUID REFERENCES staff_work(id) ON DELETE CASCADE, " +
                    "payment_date DATE NOT NULL, " +
                    "amount NUMERIC(12,2) NOT NULL CHECK (amount > 0), " +
                    "payment_method VARCHAR(50) NOT NULL DEFAULT 'CASH', " +
                    "reference VARCHAR(160), " +
                    "notes TEXT, " +
                    "recorded_by BIGINT REFERENCES app_users(id), " +
                    "created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            jdbc.execute("ALTER TABLE staff_payments ADD COLUMN IF NOT EXISTS work_id UUID REFERENCES staff_work(id) ON DELETE CASCADE");
        } catch (Exception ignored) {
        }
    }

    @GetMapping("/staff/{id}/ledger")
    public Map<String, Object> getStaffLedger(@PathVariable long id) {
        Map<String, Object> staff;
        try {
            staff = jdbc.queryForMap(
                    "SELECT u.id, u.username, u.full_name, u.role, u.active, u.created_at, u.last_login_at, " +
                    "COALESCE(w.total_earned, 0) AS total_earned, " +
                    "COALESCE(p.total_paid, 0) AS total_paid, " +
                    "(COALESCE(w.total_earned, 0) - COALESCE(p.total_paid, 0)) AS remaining_balance " +
                    "FROM app_users u " +
                    "LEFT JOIN (SELECT staff_id, SUM(amount_owed) AS total_earned FROM staff_work GROUP BY staff_id) w ON w.staff_id = u.id " +
                    "LEFT JOIN (SELECT staff_id, SUM(amount) AS total_paid FROM staff_payments GROUP BY staff_id) p ON p.staff_id = u.id " +
                    "WHERE u.id = ?", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        }

        List<Map<String, Object>> rawWorkHistory = jdbc.queryForList(
                "SELECT w.id, w.staff_id, w.order_id, o.order_number, w.work_date, w.description, " +
                "w.amount_owed, w.notes, w.created_at, rec.username AS recorded_by_user " +
                "FROM staff_work w " +
                "LEFT JOIN tailoring_orders o ON o.id = w.order_id " +
                "LEFT JOIN app_users rec ON rec.id = w.recorded_by " +
                "WHERE w.staff_id = ? " +
                "ORDER BY w.work_date DESC, w.created_at DESC", id);

        List<Map<String, Object>> paymentHistory = jdbc.queryForList(
                "SELECT p.id, p.staff_id, p.work_id, w.description AS work_description, p.payment_date, p.amount, p.payment_method, " +
                "p.reference, p.notes, p.created_at, rec.username AS recorded_by_user " +
                "FROM staff_payments p " +
                "LEFT JOIN staff_work w ON w.id = p.work_id " +
                "LEFT JOIN app_users rec ON rec.id = p.recorded_by " +
                "WHERE p.staff_id = ? " +
                "ORDER BY p.payment_date DESC, p.created_at DESC", id);

        Map<String, List<Map<String, Object>>> paymentsByWorkId = new HashMap<>();
        for (Map<String, Object> p : paymentHistory) {
            Object wid = p.get("work_id");
            if (wid != null) {
                paymentsByWorkId.computeIfAbsent(wid.toString(), k -> new ArrayList<>()).add(p);
            }
        }

        List<Map<String, Object>> workHistory = new ArrayList<>();
        for (Map<String, Object> w : rawWorkHistory) {
            Map<String, Object> entry = new LinkedHashMap<>(w);
            String widStr = String.valueOf(w.get("id"));
            List<Map<String, Object>> entryPayments = paymentsByWorkId.getOrDefault(widStr, Collections.emptyList());

            BigDecimal cost = BigDecimal.ZERO;
            Object costObj = w.get("amount_owed");
            if (costObj != null) {
                cost = new BigDecimal(costObj.toString());
            }

            BigDecimal paid = BigDecimal.ZERO;
            for (Map<String, Object> ep : entryPayments) {
                Object amt = ep.get("amount");
                if (amt != null) {
                    paid = paid.add(new BigDecimal(amt.toString()));
                }
            }

            BigDecimal remaining = cost.subtract(paid);
            if (remaining.compareTo(BigDecimal.ZERO) < 0) {
                remaining = BigDecimal.ZERO;
            }

            String status = remaining.compareTo(BigDecimal.ZERO) <= 0 ? "PAID"
                    : (paid.compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "UNPAID");

            entry.put("total_cost", cost);
            entry.put("total_paid", paid);
            entry.put("remaining_balance", remaining);
            entry.put("status", status);
            entry.put("payments", entryPayments);
            workHistory.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("staff", staff);
        out.put("totalEarned", staff.get("total_earned"));
        out.put("totalPaid", staff.get("total_paid"));
        out.put("remainingBalance", staff.get("remaining_balance"));
        out.put("workHistory", workHistory);
        out.put("paymentHistory", paymentHistory);
        return out;
    }

    @PostMapping("/staff/{id}/work")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> recordStaffWork(@PathVariable long id, @RequestBody Map<String, Object> b, Authentication auth) {
        Integer staffCount = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=?", Integer.class, id);
        if (staffCount == null || staffCount == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        }

        String description = text(b, "description");
        if (description.isBlank()) {
            description = text(b, "workDone");
        }
        if (description.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Work description is required");
        }
        if (description.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Description must not exceed 255 characters");
        }

        BigDecimal amountOwed;
        try {
            Object amtObj = b.get("totalCost") != null ? b.get("totalCost") : (b.get("amountOwed") != null ? b.get("amountOwed") : b.get("amount_owed"));
            amountOwed = new BigDecimal(amtObj == null ? "0" : amtObj.toString().trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid total cost");
        }
        if (amountOwed.compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Total cost cannot be negative");
        }

        BigDecimal payNow = BigDecimal.ZERO;
        if (b.get("payNow") != null || b.get("pay_now") != null || b.get("amountPaid") != null) {
            try {
                Object pObj = b.get("payNow") != null ? b.get("payNow") : (b.get("pay_now") != null ? b.get("pay_now") : b.get("amountPaid"));
                String pStr = pObj == null ? "0" : pObj.toString().trim();
                if (!pStr.isBlank()) {
                    payNow = new BigDecimal(pStr);
                }
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid Pay Now amount");
            }
            if (payNow.compareTo(BigDecimal.ZERO) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pay Now amount cannot be negative");
            }
            if (payNow.compareTo(amountOwed) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pay Now amount (₹" + payNow + ") cannot exceed total cost (₹" + amountOwed + ")");
            }
        }

        LocalDate workDate = LocalDate.now();
        String rawDate = text(b, "workDate").isBlank() ? text(b, "work_date") : text(b, "workDate");
        if (!rawDate.isBlank()) {
            try {
                workDate = LocalDate.parse(rawDate.trim());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid work date format (use YYYY-MM-DD)");
            }
        }

        UUID orderId = null;
        String rawOrderId = text(b, "orderId").isBlank() ? text(b, "order_id") : text(b, "orderId");
        if (!rawOrderId.isBlank()) {
            try {
                orderId = UUID.fromString(rawOrderId.trim());
            } catch (Exception ignored) {}
        }

        String notes = blank(text(b, "notes"));
        UUID workId = UUID.randomUUID();
        long currentUid = currentUserId(auth);

        jdbc.update(
                "INSERT INTO staff_work(id, staff_id, order_id, work_date, description, amount_owed, notes, recorded_by) " +
                "VALUES(?,?,?,?,?,?,?,?)",
                workId, id, orderId, java.sql.Date.valueOf(workDate), description, amountOwed, notes, currentUid > 0 ? currentUid : null);

        if (payNow.compareTo(BigDecimal.ZERO) > 0) {
            String payMethod = text(b, "paymentMethod").isBlank() ? text(b, "payment_method") : text(b, "paymentMethod");
            if (payMethod.isBlank()) payMethod = "CASH";
            String payRef = blank(text(b, "reference"));
            String rawPayNote = text(b, "paymentNote");
            String payNote = rawPayNote.isBlank() ? "Initial payment for work: " + description : rawPayNote.trim();

            UUID initialPayId = UUID.randomUUID();
            jdbc.update(
                    "INSERT INTO staff_payments(id, staff_id, work_id, payment_date, amount, payment_method, reference, notes, recorded_by) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                    initialPayId, id, workId, java.sql.Date.valueOf(workDate), payNow, payMethod, payRef, payNote, currentUid > 0 ? currentUid : null);
        }

        BigDecimal remainingBalance = amountOwed.subtract(payNow);
        Map<String, Object> resp = new java.util.LinkedHashMap<>();
        resp.put("id", workId);
        resp.put("staffId", id);
        resp.put("workDate", workDate.toString());
        resp.put("description", description);
        resp.put("totalCost", amountOwed);
        resp.put("amountOwed", amountOwed);
        resp.put("amountPaid", payNow);
        resp.put("remainingBalance", remainingBalance);
        resp.put("message", "Staff work recorded successfully");
        return resp;
    }

    @PostMapping("/staff/{id}/work/{workId}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> recordWorkEntryPayment(@PathVariable long id, @PathVariable UUID workId,
                                                      @RequestBody Map<String, Object> b, Authentication auth) {
        Integer staffCount = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=?", Integer.class, id);
        if (staffCount == null || staffCount == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        }

        Map<String, Object> work;
        try {
            work = jdbc.queryForMap("SELECT id, staff_id, amount_owed, description FROM staff_work WHERE id=? AND staff_id=?", workId, id);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff work entry not found");
        }

        BigDecimal amount;
        try {
            Object amtObj = b.get("amount");
            amount = new BigDecimal(amtObj == null ? "0" : amtObj.toString().trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment amount");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must be greater than zero");
        }

        BigDecimal totalCost = new BigDecimal(work.get("amount_owed").toString());
        BigDecimal currentPaid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM staff_payments WHERE work_id=?", BigDecimal.class, workId);
        if (currentPaid == null) currentPaid = BigDecimal.ZERO;
        BigDecimal remainingBalance = totalCost.subtract(currentPaid);
        if (remainingBalance.compareTo(BigDecimal.ZERO) < 0) remainingBalance = BigDecimal.ZERO;

        if (amount.compareTo(remainingBalance) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Payment amount of ₹" + amount + " exceeds remaining balance of ₹" + remainingBalance);
        }

        String method = text(b, "paymentMethod").isBlank() ? text(b, "payment_method") : text(b, "paymentMethod");
        if (method.isBlank()) method = "CASH";

        LocalDate paymentDate = LocalDate.now();
        String rawDate = text(b, "paymentDate").isBlank() ? text(b, "payment_date") : text(b, "paymentDate");
        if (!rawDate.isBlank()) {
            try {
                paymentDate = LocalDate.parse(rawDate.trim());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment date format (use YYYY-MM-DD)");
            }
        }

        String reference = blank(text(b, "reference"));
        String rawNotes = text(b, "notes");
        String notes = rawNotes.isBlank() ? "Payment for work: " + work.get("description") : rawNotes.trim();

        UUID paymentId = UUID.randomUUID();
        long currentUid = currentUserId(auth);

        jdbc.update(
                "INSERT INTO staff_payments(id, staff_id, work_id, payment_date, amount, payment_method, reference, notes, recorded_by) " +
                "VALUES(?,?,?,?,?,?,?,?,?)",
                paymentId, id, workId, java.sql.Date.valueOf(paymentDate), amount, method, reference, notes, currentUid > 0 ? currentUid : null);

        BigDecimal newRemaining = remainingBalance.subtract(amount);
        return Map.of(
                "id", paymentId,
                "staffId", id,
                "workId", workId,
                "paymentDate", paymentDate.toString(),
                "amount", amount,
                "paymentMethod", method,
                "remainingBalance", newRemaining,
                "message", "Payment of Rs. " + amount + " recorded against work entry successfully"
        );
    }

    @PostMapping("/staff/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> recordStaffPayment(@PathVariable long id, @RequestBody Map<String, Object> b, Authentication auth) {
        String rawWorkId = text(b, "workId").isBlank() ? text(b, "work_id") : text(b, "workId");
        if (!rawWorkId.isBlank()) {
            try {
                UUID wid = UUID.fromString(rawWorkId.trim());
                return recordWorkEntryPayment(id, wid, b, auth);
            } catch (IllegalArgumentException ignored) {}
        }

        Integer staffCount = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE id=?", Integer.class, id);
        if (staffCount == null || staffCount == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        }

        BigDecimal amount;
        try {
            Object amtObj = b.get("amount");
            amount = new BigDecimal(amtObj == null ? "0" : amtObj.toString().trim());
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment amount");
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must be greater than zero");
        }

        String method = text(b, "paymentMethod").isBlank() ? text(b, "payment_method") : text(b, "paymentMethod");
        if (method.isBlank()) {
            method = "CASH";
        }
        if (method.length() > 50) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment method is too long");
        }

        LocalDate paymentDate = LocalDate.now();
        String rawDate = text(b, "paymentDate").isBlank() ? text(b, "payment_date") : text(b, "paymentDate");
        if (!rawDate.isBlank()) {
            try {
                paymentDate = LocalDate.parse(rawDate.trim());
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment date format (use YYYY-MM-DD)");
            }
        }

        String reference = blank(text(b, "reference"));
        String notes = blank(text(b, "notes"));

        // Check overall balance owed
        BigDecimal totalEarned = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount_owed), 0) FROM staff_work WHERE staff_id=?", BigDecimal.class, id);
        BigDecimal totalPaid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM staff_payments WHERE staff_id=?", BigDecimal.class, id);
        BigDecimal overallRemaining = (totalEarned != null ? totalEarned : BigDecimal.ZERO)
                .subtract(totalPaid != null ? totalPaid : BigDecimal.ZERO);
        if (overallRemaining.compareTo(BigDecimal.ZERO) < 0) overallRemaining = BigDecimal.ZERO;

        boolean isExplicitAdvance = (notes != null && notes.toLowerCase().contains("advance"))
                || (reference != null && reference.toLowerCase().contains("advance"));
        if (!isExplicitAdvance && amount.compareTo(overallRemaining) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Payment amount of ₹" + amount + " exceeds remaining balance owed of ₹" + overallRemaining +
                    ". If this is an advance, specify 'Advance' in notes or reference.");
        }

        // Rapid duplicate submission prevention (within 3 seconds)
        OffsetDateTime recentCutoff = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(3);
        Integer recentDuplicates = jdbc.queryForObject(
                "SELECT COUNT(*) FROM staff_payments WHERE staff_id=? AND amount=? AND payment_method=? " +
                "AND created_at >= ?",
                Integer.class, id, amount, method, recentCutoff);
        if (recentDuplicates != null && recentDuplicates > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate payment detected. Please avoid double submission.");
        }

        UUID paymentId = UUID.randomUUID();
        long currentUid = currentUserId(auth);

        jdbc.update(
                "INSERT INTO staff_payments(id, staff_id, payment_date, amount, payment_method, reference, notes, recorded_by) " +
                "VALUES(?,?,?,?,?,?,?,?)",
                paymentId, id, java.sql.Date.valueOf(paymentDate), amount, method, reference, notes, currentUid > 0 ? currentUid : null);

        return Map.of(
                "id", paymentId,
                "staffId", id,
                "paymentDate", paymentDate.toString(),
                "amount", amount,
                "paymentMethod", method,
                "message", "Payment of Rs. " + amount + " to staff recorded successfully"
        );
    }

    @PostMapping({"/users", "/staff"})
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createStaff(@RequestBody Map<String,Object> b) {
        String username = text(b, "username");
        String password = text(b, "password");
        String role = text(b, "role").toUpperCase();
        String fullName = text(b, "fullName");
        if (fullName.isBlank()) {
            fullName = text(b, "full_name");
        }
        if (username.isBlank() || password.length() < 12 || !Set.of("ADMIN", "TAILOR").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide username, password of at least 12 characters, and ADMIN or TAILOR role");
        }
        boolean active = true;
        if (b.containsKey("active")) {
            active = Boolean.parseBoolean(String.valueOf(b.get("active")));
        }
        final String finalFullName = fullName.isBlank() ? username : fullName;
        final boolean finalActive = active;
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO app_users(username, full_name, password_hash, role, active) VALUES(?,?,?,?,?)", new String[]{"id"});
                ps.setString(1, username);
                ps.setString(2, finalFullName);
                ps.setString(3, encoder.encode(password));
                ps.setString(4, role);
                ps.setBoolean(5, finalActive);
                return ps;
            }, keyHolder);
            Long id;
            try {
                id = extractKey(keyHolder);
                if (id == null || id == 1L) {
                    id = jdbc.queryForObject("SELECT id FROM app_users WHERE username=?", Long.class, username);
                }
            } catch (Exception ex) {
                id = jdbc.queryForObject("SELECT id FROM app_users WHERE username=?", Long.class, username);
            }
            Map<String,Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("username", username);
            out.put("fullName", finalFullName);
            out.put("role", role);
            out.put("active", finalActive);
            return out;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
    }

    @PatchMapping({"/staff/{id}", "/users/{id}"})
    public Map<String,Object> updateStaff(@PathVariable long id, @RequestBody Map<String,Object> b) {
        Map<String,Object> existing;
        try {
            existing = jdbc.queryForMap("SELECT id, username, full_name, role, active FROM app_users WHERE id=?", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        }

        String currentRole = String.valueOf(existing.get("role"));
        boolean currentActive = Boolean.TRUE.equals(existing.get("active"));

        // Last Active Administrator Protection: Never demote or deactivate the last active admin
        if (b.containsKey("active") && !Boolean.parseBoolean(String.valueOf(b.get("active")))) {
            if ("ADMIN".equals(currentRole) && currentActive) {
                Integer remainingAdmins = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_users WHERE role='ADMIN' AND active=true AND id<>?", Integer.class, id);
                if (remainingAdmins == null || remainingAdmins == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot deactivate or demote the last active administrator account.");
                }
            }
        }

        if (b.containsKey("role")) {
            String newRole = text(b, "role").toUpperCase();
            if (!Set.of("ADMIN", "TAILOR").contains(newRole)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be ADMIN or TAILOR");
            }
            if ("ADMIN".equals(currentRole) && !"ADMIN".equals(newRole) && currentActive) {
                Integer remainingAdmins = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_users WHERE role='ADMIN' AND active=true AND id<>?", Integer.class, id);
                if (remainingAdmins == null || remainingAdmins == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot deactivate or demote the last active administrator account.");
                }
            }
            jdbc.update("UPDATE app_users SET role=? WHERE id=?", newRole, id);
        }

        if (b.containsKey("active")) {
            boolean act = Boolean.parseBoolean(String.valueOf(b.get("active")));
            jdbc.update("UPDATE app_users SET active=? WHERE id=?", act, id);
        }

        if (b.containsKey("fullName") || b.containsKey("full_name")) {
            String fn = b.containsKey("fullName") ? text(b, "fullName") : text(b, "full_name");
            if (!fn.isBlank()) {
                jdbc.update("UPDATE app_users SET full_name=? WHERE id=?", fn, id);
            }
        }

        return Map.of("id", id, "message", "Staff account updated successfully");
    }

    @PatchMapping("/users/{id}/active")
    public Map<String,String> activeUser(@PathVariable long id, @RequestBody Map<String,Object> b) {
        boolean act = Boolean.parseBoolean(String.valueOf(b.get("active")));
        if (!act) {
            String role = jdbc.queryForObject("SELECT role FROM app_users WHERE id=? AND active=true", String.class, id);
            if ("ADMIN".equals(role)) {
                Integer remainingAdmins = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_users WHERE role='ADMIN' AND active=true AND id<>?", Integer.class, id);
                if (remainingAdmins == null || remainingAdmins == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot deactivate or demote the last active administrator account.");
                }
            }
        }
        jdbc.update("UPDATE app_users SET active=? WHERE id=?", act, id);
        return Map.of("message", "User status updated");
    }

    @PatchMapping("/users/{id}/role")
    public Map<String,String> updateUserRole(@PathVariable long id, @RequestBody Map<String,Object> b) {
        String role = text(b, "role").toUpperCase();
        if (!Set.of("ADMIN", "TAILOR").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Role must be ADMIN or TAILOR");
        }
        if (!"ADMIN".equals(role)) {
            Boolean isActive = jdbc.queryForObject("SELECT active FROM app_users WHERE id=? AND role='ADMIN'", Boolean.class, id);
            if (Boolean.TRUE.equals(isActive)) {
                Integer remainingAdmins = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM app_users WHERE role='ADMIN' AND active=true AND id<>?", Integer.class, id);
                if (remainingAdmins == null || remainingAdmins == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot deactivate or demote the last active administrator account.");
                }
            }
        }
        int n = jdbc.update("UPDATE app_users SET role=? WHERE id=?", role, id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found");
        return Map.of("message", "Role updated to " + role);
    }

    @PostMapping({"/staff/{id}/reset-password", "/users/{id}/reset-password"})
    public Map<String,String> resetStaffPassword(@PathVariable long id, @RequestBody Map<String,Object> b) {
        String password = text(b, "password");
        if (password.length() < 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "New password must be at least 12 characters");
        }
        int n = jdbc.update("UPDATE app_users SET password_hash=? WHERE id=?", encoder.encode(password), id);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Staff member not found");
        return Map.of("message", "Password reset successfully");
    }

    @PatchMapping("/users/{id}/password")
    public Map<String,String> resetUserPassword(@PathVariable long id, @RequestBody Map<String,Object> b) {
        return resetStaffPassword(id, b);
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

    @GetMapping(value = "/orders/{id}/pdf", produces = "application/pdf")
    public ResponseEntity<byte[]> downloadInvoicePdf(@PathVariable UUID id) {
        byte[] pdf = pdfService.generateInvoicePdf(id);
        String orderNum = jdbc.queryForObject("SELECT order_number FROM tailoring_orders WHERE id=?", String.class, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"invoice-" + orderNum + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    @GetMapping(value = "/reports/export-excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportOverallExcel() throws IOException {
        byte[] excel = excelService.generateWorkbook();
        String filename = "jolly-ladies-tailor-data-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel);
    }

    @DeleteMapping("/orders/{id}")
    @Transactional
    public Map<String,Object> deleteOrder(@PathVariable UUID id) {
        Map<String,Object> ord;
        try {
            ord = jdbc.queryForMap("SELECT id, order_number, total, amount_paid, status FROM tailoring_orders WHERE id=? FOR UPDATE", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        String orderNum = String.valueOf(ord.get("order_number"));
        BigDecimal paid = (BigDecimal) ord.get("amount_paid");
        Integer payCount = jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_id=?", Integer.class, id);

        if ((paid != null && paid.signum() > 0) || (payCount != null && payCount > 0)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Order " + orderNum + " has recorded payment history (₹" + paid + " across " + payCount + " payment records). " +
                    "To protect financial audit history and accounting integrity, orders with payments cannot be hard-deleted. " +
                    "Please cancel or void the order instead.");
        }

        jdbc.update("DELETE FROM order_items WHERE order_id=?", id);
        jdbc.update("DELETE FROM tailoring_orders WHERE id=?", id);
        return Map.of("message", "Order " + orderNum + " deleted successfully", "id", id, "orderNumber", orderNum);
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

    private Long extractKey(KeyHolder keyHolder) {
        if (keyHolder.getKeys() != null && !keyHolder.getKeys().isEmpty()) {
            Map<String, Object> keys = keyHolder.getKeys();
            for (String col : List.of("id", "ID", "id_")) {
                if (keys.containsKey(col) && keys.get(col) instanceof Number num) {
                    return num.longValue();
                }
            }
            for (Object v : keys.values()) {
                if (v instanceof Number num) {
                    return num.longValue();
                }
            }
        }
        Number k = keyHolder.getKey();
        return k != null ? k.longValue() : 1L;
    }

    private long currentUserId(Authentication a) {
        if (a == null || a.getName() == null || a.getName().isBlank()) return 0;
        try {
            var list = jdbc.queryForList("SELECT id FROM app_users WHERE username=?", Long.class, a.getName());
            return list.isEmpty() ? 0 : list.get(0);
        } catch (Exception e) {
            return 0;
        }
    }
}
