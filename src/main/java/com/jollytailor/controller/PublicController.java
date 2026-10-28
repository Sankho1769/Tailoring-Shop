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
    private final JdbcTemplate jdbc; private final StorageService storage;
    public PublicController(JdbcTemplate jdbc, StorageService storage) { this.jdbc=jdbc; this.storage=storage; }

    @GetMapping("/shop") public Map<String,Object> shop(){
        Map<String,Object> out=new LinkedHashMap<>();
        jdbc.query("SELECT setting_key,setting_value FROM shop_settings",rs->{out.put(rs.getString(1),rs.getString(2));});
        Object key=out.get("gpay_qr_key");out.remove("gpay_qr_key");if(key!=null&&!key.toString().isBlank())out.put("gpayQrUrl",storage.publicUrl(key.toString()));
        return out;
    }
    @GetMapping("/categories") public List<Map<String,Object>> categories() {
        return jdbc.queryForList("SELECT id,name,description FROM categories WHERE active=true ORDER BY name");
    }
    @GetMapping("/products") public List<Map<String,Object>> products() {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT p.id,p.name,p.description,p.default_rate,p.image_key,c.name AS category FROM products p LEFT JOIN categories c ON c.id=p.category_id WHERE p.active=true ORDER BY c.name,p.name");
        rows.forEach(r -> { Object key=r.remove("image_key"); if(key!=null) r.put("imageUrl",storage.publicUrl(key.toString())); });
        return rows;
    }
    @PostMapping("/booking-requests") @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> booking(@RequestBody Map<String,Object> body) {
        String name=text(body,"name"), phone=text(body,"phone");
        if(name.isBlank()||phone.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Name and phone are required");
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO customer_requests(id,name,phone,requested_service,message) VALUES(?,?,?,?,?)",id,name,phone,blankToNull(text(body,"service")),blankToNull(text(body,"message")));
        return Map.of("id",id,"message","Thanks! The shop will contact you to confirm your request.");
    }
    private String text(Map<String,Object> b,String k){Object v=b.get(k);return v==null?"":String.valueOf(v).trim();}
    private String blankToNull(String s){return s.isBlank()?null:s;}
}
