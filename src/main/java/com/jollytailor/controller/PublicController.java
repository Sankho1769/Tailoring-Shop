package com.jollytailor.controller;

import com.jollytailor.service.StorageService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final JdbcTemplate jdbc;
    private final StorageService storage;
    private final ConcurrentHashMap<String, Deque<Long>> ipRequestTimestamps = new ConcurrentHashMap<>();

    public PublicController(JdbcTemplate jdbc, StorageService storage) {
        this.jdbc = jdbc;
        this.storage = storage;
    }

    private void checkRateLimit(HttpServletRequest request) {
        if (request == null) return;
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank()) {
            ip = request.getRemoteAddr();
        } else if (ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        if (ip == null || ip.isBlank()) ip = "unknown";

        long now = System.currentTimeMillis();
        Deque<Long> queue = ipRequestTimestamps.computeIfAbsent(ip, k -> new ConcurrentLinkedDeque<>());
        synchronized (queue) {
            while (!queue.isEmpty() && now - queue.peekFirst() > 60000L) {
                queue.pollFirst();
            }
            if (queue.size() >= 10) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many enquiries. Please wait a minute before submitting again.");
            }
            queue.addLast(now);
        }
    }

    @GetMapping("/shop")
    public Map<String,Object> shop() {
        Map<String,Object> out = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM shop_settings", rs -> {
            out.put(rs.getString(1), rs.getString(2));
        });
        // Internal payment credentials/QR are strictly for tailor workspace in-shop verification
        out.remove("gpay_qr_key");
        out.remove("upi_id");
        return out;
    }

    @GetMapping("/categories")
    public List<Map<String,Object>> categories() {
        return jdbc.queryForList("SELECT id, name, description FROM categories WHERE active=true ORDER BY name");
    }

    @GetMapping("/products")
    public List<Map<String,Object>> products() {
        List<Map<String,Object>> rows = jdbc.queryForList(
                "SELECT p.id, p.name, p.description, p.default_rate, p.product_type, p.stock_quantity, p.image_key, c.name AS category " +
                "FROM products p LEFT JOIN categories c ON c.id=p.category_id WHERE p.active=true ORDER BY c.name, p.name");
        rows.forEach(r -> {
            Object key = r.remove("image_key");
            if (key != null && !key.toString().isBlank()) {
                r.put("imageUrl", storage.publicUrl(key.toString()));
            }
        });
        return rows;
    }

    @PostMapping({"/contact", "/enquiry", "/booking-requests"})
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> submitEnquiry(@RequestBody Map<String,Object> body, HttpServletRequest request) {
        checkRateLimit(request);

        String name = text(body, "name");
        String rawPhone = text(body, "phone");
        String email = text(body, "email");
        String service = text(body, "service");
        if (service.isBlank()) {
            service = text(body, "requested_service");
        }
        String message = text(body, "message");

        // 1. Validate required name
        if (name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer name is required");
        }
        if (name.length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name must not exceed 160 characters");
        }

        // 2. Validate required mobile number (standard Indian mobile format)
        if (rawPhone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mobile number is required");
        }
        String cleanPhone = rawPhone.replaceAll("[^0-9]", "");
        if (cleanPhone.startsWith("91") && cleanPhone.length() == 12) {
            cleanPhone = cleanPhone.substring(2);
        } else if (cleanPhone.startsWith("0") && cleanPhone.length() == 11) {
            cleanPhone = cleanPhone.substring(1);
        }
        if (!cleanPhone.matches("^[6-9]\\d{9}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Please enter a valid 10-digit Indian mobile number (e.g. 9748546778)");
        }

        // 3. Validate optional email
        String storedEmail = null;
        if (!email.isBlank()) {
            if (email.length() > 160 || !email.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Please enter a valid email address or leave it blank");
            }
            storedEmail = email;
        }

        // 4. Validate required message
        if (message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message is required");
        }
        if (message.length() > 2000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message must not exceed 2000 characters");
        }

        // 5. Sanitize service
        String storedService = blankToNull(service);
        if (storedService != null && storedService.length() > 140) {
            storedService = storedService.substring(0, 140);
        }

        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_requests(id, name, phone, email, requested_service, message, status) VALUES(?,?,?,?,?,?,'NEW')",
                id, name, cleanPhone, storedEmail, storedService, message);
        return Map.of(
                "id", id.toString(),
                "status", "SUCCESS",
                "message", "Thank you! Your enquiry has been received. Jolly Ladies Tailor will contact you shortly."
        );
    }

    @GetMapping("/bills/{id}")
    public Map<String,Object> getPublicBill(@PathVariable UUID id, @RequestParam(required = false) String phone) {
        Map<String,Object> ord;
        try {
            ord = jdbc.queryForMap(
                    "SELECT o.id, o.order_number, o.status, " +
                    "COALESCE(o.payment_status, CASE WHEN o.total > 0 AND o.amount_paid >= o.total THEN 'PAID' WHEN o.amount_paid > 0 THEN 'PARTIALLY_PAID' ELSE 'PAYMENT_DUE' END) AS payment_status, " +
                    "o.due_date, o.subtotal, o.discount, o.total, o.amount_paid, (o.total - o.amount_paid) AS due, o.created_at, " +
                    "c.name AS customer_name, c.phone AS customer_phone " +
                    "FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id WHERE o.id=?", id);
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Bill not found");
        }

        // Optional phone verification
        if (phone != null && !phone.isBlank()) {
            String cleanParam = phone.replaceAll("[^0-9]", "");
            String cleanCust = String.valueOf(ord.get("customer_phone")).replaceAll("[^0-9]", "");
            if (!cleanCust.endsWith(cleanParam) && !cleanParam.endsWith(cleanCust)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Phone number verification failed");
            }
        }

        // Mask phone for privacy in public view
        String rawPhone = String.valueOf(ord.get("customer_phone"));
        String maskedPhone = rawPhone.length() > 4
                ? "XXXXXX" + rawPhone.substring(rawPhone.length() - 4)
                : rawPhone;
        ord.put("customer_phone_masked", maskedPhone);
        ord.remove("customer_phone");

        // Safe customer line items (excludes internal notes or staff adjustment reasons)
        ord.put("items", jdbc.queryForList(
                "SELECT item_name, quantity, unit_rate, amount FROM order_items WHERE order_id=? ORDER BY id", id));

        // Public payment receipts
        ord.put("payments", jdbc.queryForList(
                "SELECT amount, method, received_at, COALESCE(payment_type, 'PAYMENT') AS payment_type " +
                "FROM payments WHERE order_id=? ORDER BY received_at", id));

        // Shop profile details
        Map<String,String> shop = new LinkedHashMap<>();
        jdbc.query("SELECT setting_key, setting_value FROM shop_settings", rs -> {
            shop.put(rs.getString(1), rs.getString(2));
        });
        ord.put("shop", Map.of(
                "name", shop.getOrDefault("shop_name", "Jolly Ladies Tailor"),
                "address", shop.getOrDefault("shop_address", ""),
                "phone", shop.getOrDefault("shop_phone", ""),
                "whatsapp", shop.getOrDefault("whatsapp_number", "")
        ));

        return ord;
    }

    private String text(Map<String,Object> b, String k) {
        Object v = b.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }

    private String blankToNull(String s) {
        return s.isBlank() ? null : s;
    }
}
