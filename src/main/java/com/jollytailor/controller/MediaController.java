package com.jollytailor.controller;

import com.jollytailor.service.StorageService;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/media")
public class MediaController {
    private final StorageService storage; private final JdbcTemplate jdbc;
    public MediaController(StorageService storage,JdbcTemplate jdbc){this.storage=storage;this.jdbc=jdbc;}
    @PostMapping("/shop/gpay-qr") public Map<String,Object> uploadShopQr(@RequestParam("file") MultipartFile file){
        String key=storage.uploadPublicCatalogImage(file);jdbc.update("INSERT INTO shop_settings(setting_key,setting_value,updated_at) VALUES('gpay_qr_key',?,NOW()) ON CONFLICT(setting_key) DO UPDATE SET setting_value=EXCLUDED.setting_value,updated_at=NOW()",key);
        return Map.of("key",key,"url",storage.publicUrl(key));
    }
    @PostMapping("/products/{id}") public Map<String,Object> uploadProductImage(@PathVariable UUID id,@RequestParam("file") MultipartFile file){
        String key=storage.uploadPublicCatalogImage(file);int count=jdbc.update("UPDATE products SET image_key=?,updated_at=NOW() WHERE id=?",key,id);
        if(count==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Product not found");return Map.of("key",key,"url",storage.publicUrl(key));
    }
}
