package com.jollytailor.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jollytailor.service.StorageService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tailor")
public class TailorController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StorageService storage;

    public TailorController(JdbcTemplate jdbc, ObjectMapper mapper, StorageService storage) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.storage = storage;
    }

    @GetMapping("/payment-qr")
    public Map<String,Object> paymentQr() {
        Map<String,String> settings = new HashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM shop_settings", rs -> {
            settings.put(rs.getString(1), rs.getString(2));
        });
        Map<String,Object> out = new LinkedHashMap<>();
        out.put("shopName", settings.getOrDefault("shop_name", "Jolly Ladies Tailor"));
        out.put("shopAddress", settings.getOrDefault("shop_address", ""));
        out.put("shopPhone", settings.getOrDefault("shop_phone", ""));
        out.put("upiId", settings.getOrDefault("upi_id", ""));
        out.put("adminNotificationPhone", settings.getOrDefault("admin_notification_phone", ""));
        String qrKey = settings.get("gpay_qr_key");
        if (qrKey != null && !qrKey.isBlank()) {
            out.put("gpayQrUrl", storage.publicUrl(qrKey));
        } else {
            out.put("gpayQrUrl", null);
        }
        return out;
    }

    @GetMapping("/customers")
    public List<Map<String,Object>> customers(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) {
            return jdbc.queryForList("SELECT id, name, phone, address, notes, created_at, updated_at FROM customers ORDER BY updated_at DESC LIMIT 300");
        }
        String term = "%" + q.trim() + "%";
        return jdbc.queryForList("SELECT id, name, phone, address, notes, created_at, updated_at FROM customers WHERE name ILIKE ? OR phone ILIKE ? ORDER BY updated_at DESC LIMIT 100", term, term);
    }

    @PostMapping("/customers")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createCustomer(@RequestBody Map<String,Object> b) {
        String name = text(b, "name");
        String phone = text(b, "phone");
        if (name.isBlank() || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer name and phone number are required");
        }
        String cleanPhone = phone.replaceAll("[^0-9+]", "");
        if (cleanPhone.length() < 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please provide a valid phone number");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id, name, phone, address, notes) VALUES(?,?,?,?,?)",
                id, name, cleanPhone, blank(text(b, "address")), blank(text(b, "notes")));
        return getCustomer(id);
    }

    @PatchMapping("/customers/{id}")
    @Transactional
    public Map<String,Object> updateCustomer(@PathVariable UUID id, @RequestBody Map<String,Object> b) {
        String name = text(b, "name");
        String phone = text(b, "phone");
        if (name.isBlank() || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer name and phone number are required");
        }
        String cleanPhone = phone.replaceAll("[^0-9+]", "");
        int n = jdbc.update("UPDATE customers SET name=?, phone=?, address=?, notes=?, updated_at=NOW() WHERE id=?",
                name, cleanPhone, blank(text(b, "address")), blank(text(b, "notes")), id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        return getCustomer(id);
    }

    @GetMapping("/customers/{id}")
    public Map<String,Object> customer(@PathVariable UUID id) {
        return getCustomer(id);
    }

    private Map<String,Object> getCustomer(UUID id) {
        try {
            return jdbc.queryForMap("SELECT id, name, phone, address, notes, created_at, updated_at FROM customers WHERE id=?", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
    }

    @GetMapping("/customers/{id}/measurements")
    public List<Map<String,Object>> measurements(@PathVariable UUID id) {
        if (!customerExists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        return jdbc.queryForList(
                "SELECT id, customer_id, garment_type, unit, CAST(dimensions AS VARCHAR) AS dimensions, fit_preference, notes, created_at " +
                "FROM measurements WHERE customer_id=? ORDER BY created_at DESC", id);
    }

    @PostMapping("/customers/{id}/measurements")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addMeasurement(@PathVariable UUID id, @RequestBody Map<String,Object> b, Authentication auth) {
        String garment = text(b, "garmentType");
        String unit = text(b, "unit");
        if (garment.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Garment type is required");
        }
        if (!Set.of("in", "cm").contains(unit)) {
            unit = "in";
        }
        if (!customerExists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        Object dims = b.getOrDefault("dimensions", Map.of());
        String json;
        try {
            json = mapper.writeValueAsString(dims);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid measurement values");
        }
        long userId = currentUserId(auth);
        UUID mid = UUID.randomUUID();
        jdbc.update("INSERT INTO measurements(id, customer_id, garment_type, unit, dimensions, fit_preference, notes, created_by) " +
                    "VALUES(?,?,?, ?,CAST(? AS JSONB),?,?,?)",
                mid, id, garment, unit, json, blank(text(b, "fitPreference")), blank(text(b, "notes")), userId);
        return jdbc.queryForMap(
                "SELECT id, customer_id, garment_type, unit, CAST(dimensions AS VARCHAR) AS dimensions, fit_preference, notes, created_at " +
                "FROM measurements WHERE id=?", mid);
    }

    @GetMapping("/orders")
    public List<Map<String,Object>> orders(@RequestParam(required = false) String q) {
        String baseSql = "SELECT o.id, o.order_number, o.customer_id, o.measurement_id, o.status, " +
                "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                "o.due_date, o.notes, o.subtotal, o.discount, o.total, o.amount_paid, (o.total - o.amount_paid) AS due, o.created_at, " +
                "c.name AS customer, c.phone " +
                "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id ";
        if (q == null || q.isBlank()) {
            return jdbc.queryForList(baseSql + "ORDER BY o.created_at DESC LIMIT 300");
        }
        String term = "%" + q.trim() + "%";
        return jdbc.queryForList(baseSql + "WHERE o.order_number ILIKE ? OR c.name ILIKE ? OR c.phone ILIKE ? ORDER BY o.created_at DESC LIMIT 100", term, term, term);
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

        Object mId = out.get("measurement_id");
        if (mId != null) {
            try {
                Map<String,Object> mRow = jdbc.queryForMap(
                        "SELECT garment_type, unit, CAST(dimensions AS VARCHAR) AS dimensions, fit_preference, notes FROM measurements WHERE id=?", mId);
                out.put("measurement", mRow);
            } catch (Exception ignored) {}
        }
        return out;
    }

    @PostMapping("/orders")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> createOrder(@RequestBody Map<String,Object> b, Authentication auth) {
        UUID customerId = uuid(b.get("customerId"), "customerId");
        if (!customerExists(customerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }

        UUID measurementId = b.get("measurementId") == null || b.get("measurementId").toString().isBlank()
                ? null : uuid(b.get("measurementId"), "measurementId");
        if (measurementId != null) {
            Integer count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM measurements WHERE id=? AND customer_id=?", Integer.class, measurementId, customerId);
            if (count == null || count == 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected measurement profile does not belong to this customer");
            }
        }

        Object raw = b.get("items");
        if (!(raw instanceof List<?> itemList) || itemList.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one bill item is required");
        }

        UUID id = UUID.randomUUID();
        String number = "JLT-" + java.time.LocalDate.now().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        BigDecimal subtotal = BigDecimal.ZERO;
        List<Map<String,Object>> prepared = new ArrayList<>();

        long uid = currentUserId(auth);
        for (Object item : itemList) {
            if (!(item instanceof Map<?,?> m)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid bill item format");
            }
            String name = String.valueOf(itemValue(m, "name", "")).trim();
            if (name.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item name is required");
            }
            int qty;
            BigDecimal unitRate;
            try {
                qty = Integer.parseInt(String.valueOf(itemValue(m, "quantity", 1)));
                Object rateObj = m.containsKey("unitRate") ? m.get("unitRate") : itemValue(m, "rate", 0);
                unitRate = new BigDecimal(String.valueOf(rateObj));
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid quantity or rate");
            }
            if (qty < 1 || unitRate.signum() < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive and rate cannot be negative");
            }

            BigDecimal itemDiscount = BigDecimal.ZERO;
            if (m.containsKey("discount") && m.get("discount") != null && !m.get("discount").toString().isBlank()) {
                itemDiscount = decimal(m.get("discount"));
                if (itemDiscount.signum() < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item discount cannot be negative");
                }
            }

            BigDecimal lineGross = unitRate.multiply(BigDecimal.valueOf(qty));
            if (itemDiscount.compareTo(lineGross) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item discount cannot exceed line total");
            }
            BigDecimal amount = lineGross.subtract(itemDiscount);
            subtotal = subtotal.add(amount);

            String productIdStr = m.get("productId") == null ? "" : String.valueOf(m.get("productId")).trim();
            UUID prodId = null;
            BigDecimal defaultRate = null;
            if (!productIdStr.isBlank()) {
                try {
                    prodId = UUID.fromString(productIdStr);
                    var pRows = jdbc.queryForList("SELECT default_rate FROM products WHERE id=?", prodId);
                    if (!pRows.isEmpty() && pRows.get(0).get("default_rate") != null) {
                        defaultRate = (BigDecimal) pRows.get(0).get("default_rate");
                    }
                } catch (Exception ignored) {}
            }

            if (defaultRate == null) {
                if (m.containsKey("defaultRate") && m.get("defaultRate") != null && !m.get("defaultRate").toString().isBlank()) {
                    defaultRate = decimal(m.get("defaultRate"));
                } else {
                    defaultRate = unitRate;
                }
            }

            String reason = null;
            Long adjustedBy = null;
            OffsetDateTime adjustedAt = null;

            if (unitRate.compareTo(defaultRate) != 0) {
                adjustedBy = uid > 0 ? uid : null;
                adjustedAt = OffsetDateTime.now(ZoneOffset.UTC);
                Object rObj = m.get("adjustmentReason");
                reason = rObj != null ? String.valueOf(rObj).trim() : "";
                if (reason.isBlank()) {
                    reason = "Price adjusted by tailor";
                }
            }

            Map<String,Object> pMap = new HashMap<>();
            pMap.put("name", name);
            pMap.put("quantity", qty);
            pMap.put("defaultRate", defaultRate);
            pMap.put("unitRate", unitRate);
            pMap.put("discount", itemDiscount);
            pMap.put("amount", amount);
            pMap.put("productId", prodId);
            pMap.put("adjustmentReason", reason);
            pMap.put("adjustedBy", adjustedBy);
            pMap.put("adjustedAt", adjustedAt);
            prepared.add(pMap);
        }

        BigDecimal discount = decimal(b.get("discount"));
        if (discount.signum() < 0 || discount.compareTo(subtotal) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid discount amount");
        }
        BigDecimal total = subtotal.subtract(discount);

        // Check if an initial advance payment is provided
        BigDecimal advance = decimal(b.get("advanceAmount"));
        BigDecimal amountPaid = BigDecimal.ZERO;
        String paymentStatus = "PAYMENT_DUE";

        if (advance.signum() > 0) {
            if (advance.compareTo(total) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Advance cannot exceed total order amount");
            }
            amountPaid = advance;
            paymentStatus = advance.compareTo(total) >= 0 ? "PAID" : "PARTIALLY_PAID";
        }

        jdbc.update("INSERT INTO tailoring_orders(id, order_number, customer_id, measurement_id, status, payment_status, due_date, notes, subtotal, discount, total, amount_paid, created_by) " +
                    "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, number, customerId, measurementId, "NEW", paymentStatus, dateOrNull(b.get("dueDate")), blank(text(b, "notes")), subtotal, discount, total, amountPaid, uid > 0 ? uid : null);

        for (Map<String,Object> item : prepared) {
            jdbc.update("INSERT INTO order_items(id, order_id, product_id, item_name, quantity, default_rate, unit_rate, discount, amount, adjustment_reason, adjusted_by, adjusted_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), id, item.get("productId"), item.get("name"), item.get("quantity"),
                    item.get("defaultRate"), item.get("unitRate"), item.get("discount"), item.get("amount"),
                    item.get("adjustmentReason"), item.get("adjustedBy"), item.get("adjustedAt"));
        }

        if (advance.signum() > 0) {
            String method = text(b, "advanceMethod").toUpperCase();
            if (method.isBlank()) method = "UPI";
            jdbc.update("INSERT INTO payments(id, order_id, amount, method, reference, note, payment_type, received_by) " +
                        "VALUES(?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), id, advance, method, blank(text(b, "advanceRef")), "Advance on order creation", "PAYMENT", uid);
        }

        return order(id);
    }

    @PatchMapping("/orders/{id}/status")
    public Map<String,Object> updateStatus(@PathVariable UUID id, @RequestBody Map<String,Object> b) {
        String raw = text(b, "status").toUpperCase().replace(" ", "_");
        String canonical = switch (raw) {
            case "NEW", "RECEIVED" -> "NEW";
            case "IN_PROGRESS", "CUTTING", "STITCHING" -> "IN_PROGRESS";
            case "TRIAL" -> "TRIAL";
            case "READY" -> "READY";
            case "DELIVERED" -> "DELIVERED";
            case "CANCELLED" -> "CANCELLED";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid order status. Allowed: New, In Progress, Trial, Ready, Delivered, Cancelled");
        };

        int n = jdbc.update("UPDATE tailoring_orders SET status=?, updated_at=NOW() WHERE id=?", canonical, id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return Map.of("id", id, "status", canonical);
    }

    @PostMapping("/orders/{id}/payments")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> recordPayment(@PathVariable UUID id, @RequestBody Map<String,Object> b, Authentication auth) {
        BigDecimal amount = decimal(b.get("amount"));
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment amount must be greater than zero");
        }
        String method = text(b, "method").toUpperCase();
        if (!Set.of("CASH", "UPI", "CARD", "BANK_TRANSFER", "OTHER").contains(method)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment method");
        }

        Map<String,Object> ord;
        try {
            ord = jdbc.queryForMap("SELECT total, amount_paid FROM tailoring_orders WHERE id=? FOR UPDATE", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        BigDecimal total = (BigDecimal) ord.get("total");
        BigDecimal paid = (BigDecimal) ord.get("amount_paid");
        BigDecimal due = total.subtract(paid);

        if (amount.compareTo(due) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment of ₹" + amount + " exceeds outstanding balance of ₹" + due);
        }

        BigDecimal newPaid = paid.add(amount);
        String paymentStatus = newPaid.compareTo(total) >= 0 ? "PAID" : "PARTIALLY_PAID";

        UUID pid = UUID.randomUUID();
        long uid = currentUserId(auth);
        jdbc.update("INSERT INTO payments(id, order_id, amount, method, reference, note, payment_type, received_by) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                pid, id, amount, method, blank(text(b, "reference")), blank(text(b, "note")), "PAYMENT", uid);

        jdbc.update("UPDATE tailoring_orders SET amount_paid=?, payment_status=?, updated_at=NOW() WHERE id=?",
                newPaid, paymentStatus, id);

        return jdbc.queryForMap("SELECT id, order_id, amount, method, reference, note, payment_type, received_at FROM payments WHERE id=?", pid);
    }

    @PostMapping("/orders/{id}/refunds")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> recordRefund(@PathVariable UUID id, @RequestBody Map<String,Object> b, Authentication auth) {
        BigDecimal refundAmount = decimal(b.get("amount"));
        if (refundAmount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund amount must be greater than zero");
        }

        Map<String,Object> ord;
        try {
            ord = jdbc.queryForMap("SELECT total, amount_paid FROM tailoring_orders WHERE id=? FOR UPDATE", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        BigDecimal total = (BigDecimal) ord.get("total");
        BigDecimal paid = (BigDecimal) ord.get("amount_paid");

        if (refundAmount.compareTo(paid) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Refund of ₹" + refundAmount + " exceeds total amount paid so far of ₹" + paid);
        }

        BigDecimal newPaid = paid.subtract(refundAmount);
        String paymentStatus = newPaid.signum() == 0 ? "REFUNDED" : (newPaid.compareTo(total) >= 0 ? "PAID" : "PARTIALLY_PAID");

        UUID pid = UUID.randomUUID();
        long uid = currentUserId(auth);
        String reason = text(b, "reason");
        if (reason.isBlank()) reason = "Customer refund";

        jdbc.update("INSERT INTO payments(id, order_id, amount, method, reference, note, payment_type, received_by) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                pid, id, refundAmount, "REFUND", blank(text(b, "reference")), reason, "REFUND", uid);

        jdbc.update("UPDATE tailoring_orders SET amount_paid=?, payment_status=?, updated_at=NOW() WHERE id=?",
                newPaid, paymentStatus, id);

        return Map.of("id", pid, "orderId", id, "refundAmount", refundAmount, "newAmountPaid", newPaid, "paymentStatus", paymentStatus);
    }

    private Object itemValue(Map<?,?> m, String key, Object fallback) {
        Object value = m.get(key);
        return value == null ? fallback : value;
    }

    private boolean customerExists(UUID id) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM customers WHERE id=?", Integer.class, id);
        return count != null && count > 0;
    }

    private long currentUserId(Authentication a) {
        if (a == null) return 0;
        Long id = jdbc.queryForObject("SELECT id FROM app_users WHERE username=?", Long.class, a.getName());
        return id == null ? 0 : id;
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

    private UUID uuid(Object o, String field) {
        try {
            return UUID.fromString(String.valueOf(o));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field);
        }
    }

    private java.sql.Date dateOrNull(Object o) {
        if (o == null || o.toString().isBlank()) return null;
        try {
            return java.sql.Date.valueOf(LocalDate.parse(o.toString()));
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid due date");
        }
    }
}
