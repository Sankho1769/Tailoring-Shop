package com.jollytailor.controller;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/admin/requests")
public class RequestAdminController {
    private final JdbcTemplate jdbc;
    public RequestAdminController(JdbcTemplate jdbc){this.jdbc=jdbc;}
    @GetMapping public List<Map<String,Object>> all(){return jdbc.queryForList("SELECT id,name,phone,requested_service,message,status,created_at FROM customer_requests ORDER BY created_at DESC LIMIT 300");}
    @PatchMapping("/{id}/status") public Map<String,Object> status(@PathVariable UUID id,@RequestBody Map<String,Object>b){String status=String.valueOf(b.getOrDefault("status","NEW")).toUpperCase();if(!Set.of("NEW","CONTACTED","CONFIRMED","CLOSED").contains(status))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid request status");int n=jdbc.update("UPDATE customer_requests SET status=? WHERE id=?",status,id);if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Request not found");return Map.of("id",id,"status",status);}
}
