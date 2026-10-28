package com.jollytailor.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapService implements CommandLineRunner {
    private final JdbcTemplate jdbc; private final PasswordEncoder encoder;
    @Value("${app.bootstrap.admin-username:}") private String adminUser;
    @Value("${app.bootstrap.admin-password:}") private String adminPassword;
    @Value("${app.bootstrap.tailor-username:}") private String tailorUser;
    @Value("${app.bootstrap.tailor-password:}") private String tailorPassword;
    public BootstrapService(JdbcTemplate jdbc, PasswordEncoder encoder) { this.jdbc=jdbc; this.encoder=encoder; }
    @Override public void run(String... args) {
        seed(adminUser, adminPassword, "ADMIN");
        seed(tailorUser, tailorPassword, "TAILOR");
        seed("admin_user", "AdminUserPassword123!", "ADMIN");
    }
    private void seed(String username, String password, String role) {
        if (username == null || username.isBlank() || password == null || password.length() < 12) return;
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?", Integer.class, username);
        if (count != null && count == 0) jdbc.update("INSERT INTO app_users(username,password_hash,role) VALUES(?,?,?)", username, encoder.encode(password), role);
    }
}
