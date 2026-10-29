package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityAndRoleBoundaryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;

    @Test
    @DisplayName("Public endpoints are accessible anonymously")
    void testPublicEndpointsAccessibleAnonymously() throws Exception {
        mockMvc.perform(get("/api/public/shop"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/products"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/public/categories"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/bill.html"))
                .andExpect(status().isOk());

        // Anonymous visitor can submit appointment enquiry
        Map<String, Object> booking = Map.of(
                "name", "Ritu Roy",
                "phone", "9830099887",
                "service", "Blouse",
                "message", "Looking for wedding blouse stitching"
        );
        mockMvc.perform(post("/api/public/booking-requests")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(booking)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("Anonymous users cannot access private tailor or admin APIs")
    void testAnonymousBlockedFromPrivateApis() throws Exception {
        // GET /api/tailor/customers without authentication -> 401
        mockMvc.perform(get("/api/tailor/customers"))
                .andExpect(status().isUnauthorized());

        // GET /api/tailor/payment-qr without authentication -> 401
        mockMvc.perform(get("/api/tailor/payment-qr"))
                .andExpect(status().isUnauthorized());

        // GET /api/admin/stats without authentication -> 401
        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isUnauthorized());

        // GET /api/admin/price-adjustments without authentication -> 401
        mockMvc.perform(get("/api/admin/price-adjustments"))
                .andExpect(status().isUnauthorized());

        // GET /api/admin/users without authentication -> 401
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Tailor role cannot access Admin-only endpoints (returns 403 Forbidden)")
    @WithMockUser(username = "tailor_user", roles = {"TAILOR"})
    void testTailorForbiddenFromAdminEndpoints() throws Exception {
        mockMvc.perform(get("/api/admin/stats"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/price-adjustments"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/shop-settings"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/admin/requests"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Admin role can access both Admin and Tailor endpoints")
    @WithMockUser(username = "admin_user", roles = {"ADMIN"})
    void testAdminHasFullAccess() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/price-adjustments"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/admin/shop-settings"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tailor/customers"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tailor/payment-qr"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("CSRF protection blocks state-mutating requests when token is missing")
    @WithMockUser(username = "tailor_user", roles = {"TAILOR"})
    void testCsrfProtectionEnforced() throws Exception {
        mockMvc.perform(post("/api/tailor/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", "Test", "phone", "1234567890"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Security response headers are properly configured")
    void testSecurityResponseHeaders() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "strict-origin-when-cross-origin"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andExpect(header().exists("Permissions-Policy"));
    }

    @Test
    @DisplayName("Robots.txt and Sitemap.xml are publicly accessible with correct content")
    void testRobotsAndSitemapAccessible() throws Exception {
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("User-agent: *")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Disallow: /admin.html")));

        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("https://jollyladiestailor.com/")));
    }

    @Test
    @DisplayName("Login rate limiting triggers 429 Too Many Requests after 10 attempts within a minute")
    void testLoginRateLimiting() throws Exception {
        String testIp = "203.0.113.195";
        Map<String, String> creds = Map.of("username", "testadmin", "password", "wrongpass");

        // First 10 attempts fail with 401 Unauthorized (rate limiter records attempt)
        for (int i = 0; i < 10; i++) {
            mockMvc.perform(post("/api/auth/login")
                            .header("X-Forwarded-For", testIp)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(creds)))
                    .andExpect(status().isUnauthorized());
        }

        // 11th attempt is blocked by rate limiter with 429 Too Many Requests
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", testIp)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(creds)))
                .andExpect(status().isTooManyRequests())
                .andExpect(status().reason(org.hamcrest.Matchers.containsString("Too many login attempts")));
    }
}
