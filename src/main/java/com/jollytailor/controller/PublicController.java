package com.jollytailor.controller;

import com.jollytailor.service.StorageService;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/public")
public class PublicController {
    private final JdbcTemplate jdbc;
    private final StorageService storage;

    public PublicController(JdbcTemplate jdbc, StorageService storage) {
        this.jdbc = jdbc;
        this.storage = storage;
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

    @PostMapping("/booking-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> booking(@RequestBody Map<String,Object> body) {
        String name = text(body, "name");
        String phone = text(body, "phone");
        if (name.isBlank() || phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name and phone are required");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO customer_requests(id, name, phone, requested_service, message) VALUES(?,?,?,?,?)",
                id, name, phone, blankToNull(text(body, "service")), blankToNull(text(body, "message")));
        return Map.of("id", id, "message", "Thanks! The shop will contact you to confirm your request.");
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
