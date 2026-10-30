package com.jollytailor.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BootstrapService implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapService.class);

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;

    @Value("${app.bootstrap.admin-username:}")
    private String adminUser;

    @Value("${app.bootstrap.admin-password:}")
    private String adminPassword;

    @Value("${app.bootstrap.tailor-username:}")
    private String tailorUser;

    @Value("${app.bootstrap.tailor-password:}")
    private String tailorPassword;

    public BootstrapService(JdbcTemplate jdbc, PasswordEncoder encoder) {
        this.jdbc = jdbc;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        // Secure Bootstrap: Accounts are created ONLY when explicitly configured
        // via secure environment variables or profile settings (minimum 12 chars).
        // No hardcoded production credentials, default administrator accounts,
        // or runtime schema alterations exist here.
        seed(adminUser, adminPassword, "ADMIN");
        seed(tailorUser, tailorPassword, "TAILOR");
    }

    private void seed(String username, String password, String role) {
        if (username == null || username.isBlank() || password == null || password.length() < 12) {
            return;
        }

        // Safety check: Never overwrite existing user credentials or accounts during startup
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE username=?", Integer.class, username);
        if (count != null && count == 0) {
            log.info("Bootstrapping initial account for user '{}' with role '{}'", username, role);
            jdbc.update("INSERT INTO app_users(username, password_hash, role, full_name) VALUES(?,?,?,?)",
                    username, encoder.encode(password), role, "Bootstrap " + role);
        } else {
            log.info("User '{}' already exists in database; skipping bootstrap seeding to preserve credentials", username);
        }
    }
}
