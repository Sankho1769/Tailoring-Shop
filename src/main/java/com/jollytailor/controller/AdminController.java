package com.jollytailor.controller;

import com.jollytailor.service.StorageService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private final JdbcTemplate jdbc; private final StorageService storage; private final PasswordEncoder encoder;
    public AdminController(JdbcTemplate jdbc, StorageService storage, PasswordEncoder encoder){this.jdbc=jdbc;this.storage=storage;this.encoder=encoder;}

    @GetMapping("/stats") public Map<String,Object> stats(@RequestParam(defaultValue="month") String range){
        String period=switch(range){case "today"->"today";case "7d"->"7d";case "month"->"month";case "all"->"all";default->throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"range must be today, 7d, month or all");};
        String where=switch(period){case "today"->"created_at >= CURRENT_DATE";case "7d"->"created_at >= NOW() - INTERVAL '7 days'";case "month"->"created_at >= date_trunc('month', NOW())";default->"TRUE";};
        Map<String,Object> out=new LinkedHashMap<>();out.put("range",period);
        out.put("orders",jdbc.queryForObject("SELECT COUNT(*) FROM tailoring_orders WHERE "+where,Long.class));
        out.put("billed",zero(jdbc.queryForObject("SELECT COALESCE(SUM(total),0) FROM tailoring_orders WHERE "+where,BigDecimal.class)));
        out.put("collected",zero(jdbc.queryForObject("SELECT COALESCE(SUM(amount_paid),0) FROM tailoring_orders WHERE "+where,BigDecimal.class)));
        out.put("due",zero(jdbc.queryForObject("SELECT COALESCE(SUM(GREATEST(total-amount_paid,0)),0) FROM tailoring_orders WHERE "+where,BigDecimal.class)));
        out.put("customers",jdbc.queryForObject("SELECT COUNT(*) FROM customers",Long.class));
        out.put("products",jdbc.queryForObject("SELECT COUNT(*) FROM products WHERE active=true",Long.class));
        out.put("requests",jdbc.queryForObject("SELECT COUNT(*) FROM customer_requests WHERE status='NEW'",Long.class));
        out.put("salesByDay",jdbc.queryForList("SELECT TO_CHAR(d.day,'DD Mon') AS day, COALESCE(SUM(o.total),0) AS sales FROM generate_series(CURRENT_DATE - INTERVAL '6 days',CURRENT_DATE,INTERVAL '1 day') d(day) LEFT JOIN tailoring_orders o ON o.created_at::date=d.day::date GROUP BY d.day ORDER BY d.day"));
        out.put("topServices",jdbc.queryForList("SELECT item_name AS name, SUM(quantity) AS quantity, SUM(amount) AS revenue FROM order_items i JOIN tailoring_orders o ON o.id=i.order_id WHERE "+where.replace("created_at", "o.created_at")+" GROUP BY item_name ORDER BY revenue DESC LIMIT 8"));
        out.put("recentOrders",jdbc.queryForList("SELECT o.id,o.order_number,o.status,o.total,o.amount_paid,(o.total-o.amount_paid) AS due,o.created_at,c.name AS customer,c.phone FROM tailoring_orders o JOIN customers c ON c.id=o.customer_id ORDER BY o.created_at DESC LIMIT 10"));
        out.put("paymentMethods",jdbc.queryForList("SELECT method,COALESCE(SUM(amount),0) AS amount,COUNT(*) AS transactions FROM payments WHERE received_at >= CASE WHEN ?='today' THEN CURRENT_DATE WHEN ?='7d' THEN NOW()-INTERVAL '7 days' WHEN ?='month' THEN date_trunc('month',NOW()) ELSE '-infinity'::timestamptz END GROUP BY method ORDER BY amount DESC",period,period,period));
        return out;
    }
    @GetMapping("/shop-settings") public Map<String,Object> shopSettings(){Map<String,Object> out=new LinkedHashMap<>();jdbc.query("SELECT setting_key,setting_value FROM shop_settings",rs->{out.put(rs.getString(1),rs.getString(2));});return out;}
    @PutMapping("/shop-settings") public Map<String,String> saveShopSettings(@RequestBody Map<String,Object> body){
        for(String key:List.of("shop_name","shop_address","shop_phone","whatsapp_number","upi_id")){
            if(body.containsKey(key)){String value=body.get(key)==null?"":body.get(key).toString().trim();if(value.length()>1000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Setting is too long");jdbc.update("INSERT INTO shop_settings(setting_key,setting_value,updated_at) VALUES(?,?,NOW()) ON CONFLICT(setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value,updated_at=NOW()",key,value);}
        }
        return Map.of("message","Shop settings saved");
    }
    @GetMapping("/categories") public List<Map<String,Object>> categories(){return jdbc.queryForList("SELECT id,name,description,active,created_at FROM categories ORDER BY name");}
    @PostMapping("/categories") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createCategory(@RequestBody Map<String,Object> b){String name=text(b,"name");if(name.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Category name required");try{Long id=jdbc.queryForObject("INSERT INTO categories(name,description) VALUES(?,?) RETURNING id",Long.class,name,blank(text(b,"description")));return Map.of("id",id,"name",name);}catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Category already exists");}}
    @DeleteMapping("/categories/{id}") public Map<String,String> deleteCategory(@PathVariable long id){jdbc.update("UPDATE categories SET active=false WHERE id=?",id);return Map.of("message","Category deactivated");}
    @GetMapping("/products") public List<Map<String,Object>> products(){return jdbc.queryForList("SELECT p.id,p.name,p.description,p.default_rate,p.image_key,p.active,c.id AS category_id,c.name AS category FROM products p LEFT JOIN categories c ON c.id=p.category_id ORDER BY p.name");}
    @PostMapping("/products") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createProduct(@RequestBody Map<String,Object> b){String name=text(b,"name");if(name.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Product name required");BigDecimal rate=decimal(b.get("defaultRate"));if(rate.signum()<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Rate cannot be negative");Long cat=longOrNull(b.get("categoryId"));UUID id=UUID.randomUUID();try{jdbc.update("INSERT INTO products(id,category_id,name,description,default_rate) VALUES(?,?,?,?,?)",id,cat,name,blank(text(b,"description")),rate);return Map.of("id",id,"name",name,"defaultRate",rate);}catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Product already exists");}}
    @PatchMapping("/products/{id}") public Map<String,String> updateProduct(@PathVariable UUID id,@RequestBody Map<String,Object>b){if(b.containsKey("name"))jdbc.update("UPDATE products SET name=?,updated_at=NOW() WHERE id=?",text(b,"name"),id);if(b.containsKey("defaultRate"))jdbc.update("UPDATE products SET default_rate=?,updated_at=NOW() WHERE id=?",decimal(b.get("defaultRate")),id);if(b.containsKey("active"))jdbc.update("UPDATE products SET active=?,updated_at=NOW() WHERE id=?",Boolean.parseBoolean(String.valueOf(b.get("active"))),id);if(b.containsKey("description"))jdbc.update("UPDATE products SET description=?,updated_at=NOW() WHERE id=?",blank(text(b,"description")),id);if(b.containsKey("categoryId"))jdbc.update("UPDATE products SET category_id=?,updated_at=NOW() WHERE id=?",longOrNull(b.get("categoryId")),id);return Map.of("message","Product updated");}
    @PostMapping("/products/{id}/image") public Map<String,Object> productImage(@PathVariable UUID id,@RequestParam("file") MultipartFile file){String key=storage.uploadPublicCatalogImage(file);int n=jdbc.update("UPDATE products SET image_key=?,updated_at=NOW() WHERE id=?",key,id);if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");return Map.of("imageKey",key,"message","Image uploaded");}
    @GetMapping("/users") public List<Map<String,Object>> users(){return jdbc.queryForList("SELECT id,username,role,active,created_at FROM app_users ORDER BY id");}
    @PostMapping("/users") @ResponseStatus(HttpStatus.CREATED) public Map<String,Object> createUser(@RequestBody Map<String,Object>b){String username=text(b,"username"),password=text(b,"password"),role=text(b,"role").toUpperCase();if(username.isBlank()||password.length()<12||!Set.of("ADMIN","TAILOR").contains(role))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Provide username, password of at least 12 characters, and ADMIN or TAILOR role");try{Long id=jdbc.queryForObject("INSERT INTO app_users(username,password_hash,role) VALUES(?,?,?) RETURNING id",Long.class,username,encoder.encode(password),role);return Map.of("id",id,"username",username,"role",role);}catch(org.springframework.dao.DuplicateKeyException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"Username already exists");}}
    @PatchMapping("/users/{id}/active") public Map<String,String> activeUser(@PathVariable long id,@RequestBody Map<String,Object>b){jdbc.update("UPDATE app_users SET active=? WHERE id=?",Boolean.parseBoolean(String.valueOf(b.get("active"))),id);return Map.of("message","User status updated");}
    private String text(Map<String,Object>b,String k){Object v=b.get(k);return v==null?"":String.valueOf(v).trim();}
    private String blank(String s){return s.isBlank()?null:s;}
    private BigDecimal decimal(Object o){try{return new BigDecimal(o==null?"0":o.toString());}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid numeric value");}}
    private Long longOrNull(Object o){if(o==null||o.toString().isBlank())return null;try{return Long.valueOf(o.toString());}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid category ID");}}
    private BigDecimal zero(BigDecimal b){return b==null?BigDecimal.ZERO:b;}
}
