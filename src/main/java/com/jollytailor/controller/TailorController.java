package com.jollytailor.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jollytailor.service.InvoicePdfService;
import com.jollytailor.service.StorageService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/tailor")
public class TailorController {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final StorageService storage;
    private final InvoicePdfService pdfService;

    public TailorController(JdbcTemplate jdbc, ObjectMapper mapper, StorageService storage, InvoicePdfService pdfService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.storage = storage;
        this.pdfService = pdfService;
    }

    public static String normalizePhone(String raw) {
        if (raw == null) return "";
        try {
            raw = java.net.URLDecoder.decode(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {
        }
        String trimmed = raw.trim();
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.startsWith("91") && digits.length() > 10) {
            digits = digits.substring(2);
        } else if (digits.startsWith("0") && digits.length() > 10) {
            digits = digits.substring(1);
        }
        return digits.isEmpty() ? trimmed.replaceAll("[^0-9+]", "") : digits;
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
        String upi = settings.get("upi_id");
        if (upi == null || upi.isBlank() || upi.equalsIgnoreCase("jollytailor@okaxis")) {
            upi = "jollybanerjee2021@oksbi";
        }
        out.put("upiId", upi);
        out.put("adminNotificationPhone", settings.getOrDefault("admin_notification_phone", ""));
        String qrKey = settings.get("gpay_qr_key");
        if (qrKey != null && !qrKey.isBlank()) {
            out.put("gpayQrUrl", storage.publicUrl(qrKey));
        } else {
            out.put("gpayQrUrl", "/images/jolly-ladies-tailor-qr.jpg");
        }
        return out;
    }

    @GetMapping("/customers")
    public List<Map<String,Object>> customers(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) {
            return jdbc.queryForList("SELECT id, name, phone, address, notes, created_at, updated_at FROM customers ORDER BY updated_at DESC LIMIT 300");
        }
        String term = "%" + q.trim() + "%";
        String norm = normalizePhone(q);
        String normTerm = norm.isBlank() ? term : "%" + norm + "%";
        return jdbc.queryForList("SELECT id, name, phone, address, notes, created_at, updated_at FROM customers WHERE name ILIKE ? OR phone ILIKE ? OR phone ILIKE ? ORDER BY updated_at DESC LIMIT 100", term, term, normTerm);
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
        if (name.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer name must not exceed 160 characters");
        }
        String cleanPhone = normalizePhone(phone);
        String rawDigits = phone.replaceAll("[^0-9]", "");
        if (rawDigits.length() < 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Please provide a valid phone number (at least 7 digits)");
        }

        // Safe duplicate customer check:
        // If an existing customer has the SAME name AND the SAME phone, reject as duplicate
        var duplicates = jdbc.queryForList(
                "SELECT id, name, phone FROM customers WHERE (phone=? OR phone=?) AND LOWER(TRIM(name)) = LOWER(TRIM(?)) LIMIT 1",
                cleanPhone, phone.trim(), name.trim());
        if (!duplicates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Customer '" + name.trim() + "' is already registered with phone " + cleanPhone + ". Please select the existing customer.");
        }

        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id, name, phone, address, notes) VALUES(?,?,?,?,?)",
                id, name, cleanPhone, blank(text(b, "address")), blank(text(b, "notes")));
        return getCustomer(id);
    }

    @RequestMapping(value = "/customers/{id}", method = {RequestMethod.PATCH, RequestMethod.PUT})
    @Transactional
    public Map<String,Object> updateCustomer(@PathVariable UUID id, @RequestBody Map<String,Object> b) {
        String name = text(b, "name");
        String phone = text(b, "phone");
        if (name.isBlank() || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer name and phone number are required");
        }
        if (name.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer name must not exceed 160 characters");
        }
        String cleanPhone = normalizePhone(phone);
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

    @GetMapping("/customers/by-phone")
    public Map<String,Object> customerByPhone(@RequestParam String phone) {
        String cleanPhone = normalizePhone(phone);
        String raw = phone.trim();
        String digits = phone.replaceAll("[^0-9]", "");
        var rows = jdbc.queryForList(
                "SELECT id, name, phone, address, notes, created_at, updated_at FROM customers " +
                "WHERE phone=? OR phone=? OR phone LIKE ? OR ? LIKE CONCAT('%', phone) " +
                "ORDER BY updated_at DESC LIMIT 1",
                cleanPhone, raw, "%" + cleanPhone, digits);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found with this phone number");
        }
        return rows.get(0);
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

    @GetMapping("/customers/{id}/orders")
    public List<Map<String,Object>> customerOrders(@PathVariable UUID id) {
        if (!customerExists(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Customer not found");
        }
        return jdbc.queryForList(
                "SELECT o.id, o.order_number, o.customer_id, o.measurement_id, o.status, " +
                "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                "o.due_date, o.notes, o.subtotal, o.discount, o.total, o.amount_paid, (o.total - o.amount_paid) AS due, o.created_at " +
                "FROM tailoring_orders o WHERE o.customer_id=? ORDER BY o.created_at DESC", id);
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
                    var pRows = jdbc.queryForList("SELECT id, name, default_rate, product_type, stock_quantity FROM products WHERE id=? FOR UPDATE", prodId);
                    if (!pRows.isEmpty()) {
                        var pRow = pRows.get(0);
                        if (pRow.get("default_rate") != null) {
                            defaultRate = (BigDecimal) pRow.get("default_rate");
                        }
                        String pType = String.valueOf(pRow.get("product_type"));
                        if ("READY_MADE".equalsIgnoreCase(pType)) {
                            int available = pRow.get("stock_quantity") == null ? 0 : ((Number) pRow.get("stock_quantity")).intValue();
                            if (available < qty) {
                                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Insufficient inventory stock for " + name + ". Available: " + available + ", requested: " + qty);
                            }
                            int decremented = jdbc.update(
                                    "UPDATE products SET stock_quantity = stock_quantity - ?, updated_at=NOW() WHERE id=? AND stock_quantity >= ?",
                                    qty, prodId, qty);
                            if (decremented == 0) {
                                throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "Stock unavailable for " + name + " due to concurrent checkout. Please retry.");
                            }
                        }
                    }
                } catch (ResponseStatusException rse) {
                    throw rse;
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

    @PatchMapping("/orders/{id}")
    @Transactional
    public Map<String,Object> updateOrder(@PathVariable UUID id, @RequestBody Map<String,Object> b, Authentication auth) {
        Map<String,Object> existing;
        try {
            existing = jdbc.queryForMap("SELECT id, customer_id, measurement_id, due_date, notes, status, total, amount_paid FROM tailoring_orders WHERE id=? FOR UPDATE", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        UUID customerId = (UUID) existing.get("customer_id");
        long uid = currentUserId(auth);

        // Update items and recalculate totals if items provided
        if (b.containsKey("items") && b.get("items") instanceof List<?> itemList && !itemList.isEmpty()) {
            BigDecimal subtotal = BigDecimal.ZERO;
            List<Map<String,Object>> prepared = new ArrayList<>();

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
                        var pRows = jdbc.queryForList("SELECT id, name, default_rate FROM products WHERE id=?", prodId);
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

            BigDecimal discount = b.containsKey("discount") ? decimal(b.get("discount")) : BigDecimal.ZERO;
            if (discount.signum() < 0 || discount.compareTo(subtotal) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid discount amount");
            }
            BigDecimal total = subtotal.subtract(discount);

            // Reconcile amount already recorded in payments table
            BigDecimal recordedPaid = jdbc.queryForObject(
                "SELECT COALESCE(SUM(CASE WHEN payment_type='PAYMENT' THEN amount ELSE -amount END), 0) FROM payments WHERE order_id=?",
                BigDecimal.class, id);
            if (recordedPaid == null || recordedPaid.signum() < 0) {
                recordedPaid = BigDecimal.ZERO;
            }

            String paymentStatus = total.signum() == 0 || recordedPaid.compareTo(total) >= 0 ? "PAID"
                    : (recordedPaid.signum() > 0 ? "PARTIALLY_PAID" : "PAYMENT_DUE");

            jdbc.update("UPDATE tailoring_orders SET subtotal=?, discount=?, total=?, amount_paid=?, payment_status=?, updated_at=NOW() WHERE id=?",
                    subtotal, discount, total, recordedPaid, paymentStatus, id);

            // Replace order items
            jdbc.update("DELETE FROM order_items WHERE order_id=?", id);
            for (Map<String,Object> item : prepared) {
                jdbc.update("INSERT INTO order_items(id, order_id, product_id, item_name, quantity, default_rate, unit_rate, discount, amount, adjustment_reason, adjusted_by, adjusted_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                        UUID.randomUUID(), id, item.get("productId"), item.get("name"), item.get("quantity"),
                        item.get("defaultRate"), item.get("unitRate"), item.get("discount"), item.get("amount"),
                        item.get("adjustmentReason"), item.get("adjustedBy"), item.get("adjustedAt"));
            }
        }

        if (b.containsKey("notes")) {
            jdbc.update("UPDATE tailoring_orders SET notes=?, updated_at=NOW() WHERE id=?", blank(text(b, "notes")), id);
        }

        if (b.containsKey("dueDate")) {
            jdbc.update("UPDATE tailoring_orders SET due_date=?, updated_at=NOW() WHERE id=?", dateOrNull(b.get("dueDate")), id);
        }

        if (b.containsKey("measurementId")) {
            UUID mid = b.get("measurementId") == null || b.get("measurementId").toString().isBlank()
                    ? null : uuid(b.get("measurementId"), "measurementId");
            if (mid != null) {
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM measurements WHERE id=? AND customer_id=?", Integer.class, mid, customerId);
                if (count == null || count == 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Selected measurement profile does not belong to this customer");
                }
            }
            jdbc.update("UPDATE tailoring_orders SET measurement_id=?, updated_at=NOW() WHERE id=?", mid, id);
        }

        if (b.containsKey("status") && b.get("status") != null && !b.get("status").toString().isBlank()) {
            String raw = text(b, "status").toUpperCase().replace(" ", "_");
            String canonical = switch (raw) {
                case "NEW", "RECEIVED" -> "NEW";
                case "IN_PROGRESS", "CUTTING", "STITCHING" -> "IN_PROGRESS";
                case "TRIAL" -> "TRIAL";
                case "READY" -> "READY";
                case "DELIVERED" -> "DELIVERED";
                case "CANCELLED" -> "CANCELLED";
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid order status");
            };
            String oldStatus = String.valueOf(existing.get("status"));
            jdbc.update("UPDATE tailoring_orders SET status=?, updated_at=NOW() WHERE id=?", canonical, id);

            if ("CANCELLED".equals(canonical) && !"CANCELLED".equals(oldStatus)) {
                List<Map<String,Object>> items = jdbc.queryForList(
                        "SELECT product_id, quantity FROM order_items WHERE order_id=?", id);
                for (Map<String,Object> it : items) {
                    Object pId = it.get("product_id");
                    if (pId != null) {
                        int q = ((Number) it.get("quantity")).intValue();
                        jdbc.update("UPDATE products SET stock_quantity = stock_quantity + ?, updated_at=NOW() WHERE id=? AND product_type='READY_MADE'", q, pId);
                    }
                }
            }
        }

        return order(id);
    }

    @PatchMapping("/orders/{id}/status")
    @Transactional
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

        Map<String,Object> currentOrd;
        try {
            currentOrd = jdbc.queryForMap("SELECT status FROM tailoring_orders WHERE id=? FOR UPDATE", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        String oldStatus = String.valueOf(currentOrd.get("status"));

        int n = jdbc.update("UPDATE tailoring_orders SET status=?, updated_at=NOW() WHERE id=?", canonical, id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }

        // Restock inventory if cancelling previously active order
        if ("CANCELLED".equals(canonical) && !"CANCELLED".equals(oldStatus)) {
            List<Map<String,Object>> items = jdbc.queryForList(
                    "SELECT product_id, quantity FROM order_items WHERE order_id=?", id);
            for (Map<String,Object> it : items) {
                Object pId = it.get("product_id");
                if (pId != null) {
                    int q = ((Number) it.get("quantity")).intValue();
                    jdbc.update("UPDATE products SET stock_quantity = stock_quantity + ?, updated_at=NOW() WHERE id=? AND product_type='READY_MADE'", q, pId);
                }
            }
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

        // Idempotency and rapid double-submission check
        String idempotencyKey = text(b, "idempotencyKey");
        if (!idempotencyKey.isBlank()) {
            var existingWithKey = jdbc.queryForList(
                "SELECT id, order_id, amount, method, reference, note, payment_type, received_at FROM payments WHERE order_id=? AND reference=?",
                id, "IDEMP:" + idempotencyKey);
            if (!existingWithKey.isEmpty()) {
                Map<String,Object> res = new HashMap<>(existingWithKey.get(0));
                res.put("order", order(id));
                return res;
            }
        }

        var recentPayments = jdbc.queryForList(
            "SELECT id, amount, method, received_at FROM payments WHERE order_id=? ORDER BY received_at DESC LIMIT 1", id);
        if (!recentPayments.isEmpty()) {
            var last = recentPayments.get(0);
            BigDecimal lastAmt = (BigDecimal) last.get("amount");
            String lastMethod = String.valueOf(last.get("method"));
            Object recAt = last.get("received_at");
            if (lastAmt.compareTo(amount) == 0 && lastMethod.equalsIgnoreCase(method) && recAt != null) {
                Instant lastTime = recAt instanceof java.sql.Timestamp ts ? ts.toInstant()
                        : (recAt instanceof OffsetDateTime odt ? odt.toInstant() : null);
                if (lastTime != null && Instant.now().minusSeconds(3).isBefore(lastTime)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Duplicate payment detected. This payment was already recorded.");
                }
            }
        }

        BigDecimal newPaid = paid.add(amount);
        String paymentStatus = newPaid.compareTo(total) >= 0 ? "PAID" : "PARTIALLY_PAID";

        UUID pid = UUID.randomUUID();
        long uid = currentUserId(auth);
        String ref = blank(text(b, "reference"));
        if (!idempotencyKey.isBlank() && ref == null) {
            ref = "IDEMP:" + idempotencyKey;
        }

        jdbc.update("INSERT INTO payments(id, order_id, amount, method, reference, note, payment_type, received_by) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                pid, id, amount, method, ref, blank(text(b, "note")), "PAYMENT", uid);

        jdbc.update("UPDATE tailoring_orders SET amount_paid=?, payment_status=?, updated_at=NOW() WHERE id=?",
                newPaid, paymentStatus, id);

        Map<String,Object> payMap = jdbc.queryForMap(
            "SELECT id, order_id, amount, method, reference, note, payment_type, received_at FROM payments WHERE id=?", pid);
        Map<String,Object> out = new HashMap<>(payMap);
        out.put("order", order(id));
        return out;
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
