package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminEnhancementsAndDebtSeparationIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Historical Debt Separation: Distinguishes current order balance, previous outstanding balance, and overall customer debt")
    @WithMockUser(username = "tailor1", roles = {"TAILOR"})
    void testHistoricalDebtSeparationInOrderAndInvoice() throws Exception {
        UUID customerId = UUID.randomUUID();
        String customerPhone = "98300" + String.valueOf(System.currentTimeMillis()).substring(7);
        jdbc.update("INSERT INTO customers(id, name, phone, address, notes) VALUES(?,?,?,?,?)",
                customerId, "Ananya Roy Debt Test", customerPhone, "10 Lansdowne, Kolkata", "Regular customer");

        // Order 1: Total 800, Paid 300 -> Due 500 (Historical open debt)
        UUID order1Id = UUID.randomUUID();
        String order1Number = "JLT-DEBT-1-" + System.currentTimeMillis();
        jdbc.update("INSERT INTO tailoring_orders(id, order_number, customer_id, status, payment_status, subtotal, discount, total, amount_paid) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                order1Id, order1Number, customerId, "IN_PROGRESS", "PARTIALLY_PAID",
                new BigDecimal("800.00"), BigDecimal.ZERO, new BigDecimal("800.00"), new BigDecimal("300.00"));

        // Order 2 (Current Order): Total 1000, Paid 400 -> Current Due 600
        UUID order2Id = UUID.randomUUID();
        String order2Number = "JLT-DEBT-2-" + System.currentTimeMillis();
        jdbc.update("INSERT INTO tailoring_orders(id, order_number, customer_id, status, payment_status, subtotal, discount, total, amount_paid) " +
                    "VALUES(?,?,?,?,?,?,?,?,?)",
                order2Id, order2Number, customerId, "NEW", "PARTIALLY_PAID",
                new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal("1000.00"), new BigDecimal("400.00"));

        jdbc.update("INSERT INTO order_items(id, order_id, item_name, quantity, default_rate, unit_rate, discount, amount) " +
                    "VALUES(?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), order2Id, "Designer Kurti", 1, new BigDecimal("1000.00"), new BigDecimal("1000.00"), BigDecimal.ZERO, new BigDecimal("1000.00"));

        // Check Tailor order endpoint returns separated previous_orders_due (500) and overall_customer_due (1100)
        mockMvc.perform(get("/api/tailor/orders/" + order2Id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order_number", is(order2Number)))
                .andExpect(jsonPath("$.total", is(1000.0)))
                .andExpect(jsonPath("$.amount_paid", is(400.0)))
                .andExpect(jsonPath("$.due", is(600.0)))
                .andExpect(jsonPath("$.previous_orders_due", is(500.0)))
                .andExpect(jsonPath("$.overall_customer_due", is(1100.0)));

        // Verify PDF invoice generation includes customer statement without corrupting order total
        byte[] pdfBytes = mockMvc.perform(get("/api/tailor/orders/" + order2Id + "/pdf"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", is("application/pdf")))
                .andReturn().getResponse().getContentAsByteArray();

        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);
        assertTrue(new String(pdfBytes, 0, 8).startsWith("%PDF-"));
    }

    @Test
    @DisplayName("Admin Order Measurement Snapshot: Fetches attached snapshot or returns friendly empty state")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testAdminOrderMeasurementSnapshot() throws Exception {
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers(id, name, phone) VALUES(?, ?, ?)",
                customerId, "Rupa Ganguly", "98322" + String.valueOf(System.currentTimeMillis()).substring(7));

        // 1. Order with measurement snapshot attached
        UUID measureId = UUID.randomUUID();
        String dimsJson = "{\"chest\":36,\"waist\":30,\"length\":42,\"shoulder\":14.5}";
        jdbc.update("INSERT INTO measurements(id, customer_id, garment_type, unit, dimensions, fit_preference, notes) " +
                    "VALUES(?,?,?,CAST(? AS JSONB),?,?,?)",
                measureId, customerId, "Blouse", "in", dimsJson, "Comfort Fit", "Dori at back");

        UUID orderWithMeasure = UUID.randomUUID();
        String orderNum1 = "JLT-M-1-" + System.currentTimeMillis();
        jdbc.update("INSERT INTO tailoring_orders(id, order_number, customer_id, measurement_id, status, subtotal, total) " +
                    "VALUES(?,?,?,?,?,?,?)",
                orderWithMeasure, orderNum1, customerId, measureId, "NEW", new BigDecimal("450.00"), new BigDecimal("450.00"));

        mockMvc.perform(get("/api/admin/orders/" + orderWithMeasure + "/measurement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMeasurement", is(true)))
                .andExpect(jsonPath("$.orderNumber", is(orderNum1)))
                .andExpect(jsonPath("$.customerName", is("Rupa Ganguly")))
                .andExpect(jsonPath("$.measurement.garment_type", is("Blouse")))
                .andExpect(jsonPath("$.measurement.unit", is("in")))
                .andExpect(jsonPath("$.measurement.fit_preference", is("Comfort Fit")))
                .andExpect(jsonPath("$.measurement.notes", is("Dori at back")));

        // 2. Order without measurement snapshot (e.g. readymade item)
        UUID orderWithoutMeasure = UUID.randomUUID();
        String orderNum2 = "JLT-M-2-" + System.currentTimeMillis();
        jdbc.update("INSERT INTO tailoring_orders(id, order_number, customer_id, measurement_id, status, subtotal, total) " +
                    "VALUES(?,?,?,?,?,?,?)",
                orderWithoutMeasure, orderNum2, customerId, null, "NEW", new BigDecimal("200.00"), new BigDecimal("200.00"));

        mockMvc.perform(get("/api/admin/orders/" + orderWithoutMeasure + "/measurement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasMeasurement", is(false)))
                .andExpect(jsonPath("$.orderNumber", is(orderNum2)))
                .andExpect(jsonPath("$.message", containsString("No measurement snapshot")));
    }

    @Test
    @DisplayName("Staff Management: Create, list, edit, reset-password, and protect last active administrator")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testStaffManagementAndLastAdminProtection() throws Exception {
        String uniqueSuffix = String.format("%04d", (int)(Math.random() * 9000) + 1000);
        String tailorUser = "staff_tailor_" + uniqueSuffix;
        String adminUser = "staff_admin_" + uniqueSuffix;

        // 1. Create a new TAILOR staff member
        Map<String, Object> newStaffReq = Map.of(
                "fullName", "Master Suman " + uniqueSuffix,
                "username", tailorUser,
                "role", "TAILOR",
                "password", "TailorSecret1234!"
        );

        String createRes = mockMvc.perform(post("/api/admin/staff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(newStaffReq)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username", is(tailorUser)))
                .andExpect(jsonPath("$.fullName", is("Master Suman " + uniqueSuffix)))
                .andExpect(jsonPath("$.role", is("TAILOR")))
                .andExpect(jsonPath("$.active", is(true)))
                .andReturn().getResponse().getContentAsString();

        Map<?,?> createdStaff = mapper.readValue(createRes, Map.class);
        long staffId = ((Number) createdStaff.get("id")).longValue();

        // 2. List staff: should include full_name and last_login_at without exposing password_hash
        mockMvc.perform(get("/api/admin/staff"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].password_hash").doesNotExist())
                .andExpect(jsonPath("$[?(@.username == '" + tailorUser + "')].full_name", hasItem("Master Suman " + uniqueSuffix)));

        // 3. Edit staff: update full name and role
        mockMvc.perform(patch("/api/admin/staff/" + staffId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "fullName", "Senior Master Suman",
                                "role", "TAILOR",
                                "active", true
                        ))))
                .andExpect(status().isOk());

        // 4. Reset staff password
        mockMvc.perform(post("/api/admin/staff/" + staffId + "/reset-password")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("password", "BrandNewPass1234!"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("successfully")));

        // 5. Create a secondary admin
        String secondAdminRes = mockMvc.perform(post("/api/admin/staff")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of(
                                "fullName", "Second Admin " + uniqueSuffix,
                                "username", adminUser,
                                "role", "ADMIN",
                                "password", "AdminSecret1234!"
                        ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long secondAdminId = ((Number) mapper.readValue(secondAdminRes, Map.class).get("id")).longValue();

        // Deactivate the secondary admin (Allowed, because primary admin is still active)
        mockMvc.perform(patch("/api/admin/staff/" + secondAdminId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isOk());

        // 6. Security Rule: Attempt to deactivate the sole remaining active admin -> MUST REJECT (400 Bad Request)
        Long primaryAdminId = jdbc.queryForObject(
                "SELECT id FROM app_users WHERE role='ADMIN' AND active=true ORDER BY id LIMIT 1", Long.class);
        assertNotNull(primaryAdminId);

        // Ensure primaryAdminId is the sole active administrator
        jdbc.update("UPDATE app_users SET active=false WHERE role='ADMIN' AND id<>?", primaryAdminId);

        mockMvc.perform(patch("/api/admin/staff/" + primaryAdminId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("active", false))))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("Cannot deactivate or demote the last active administrator")));

        // Attempt to demote the sole active admin to TAILOR -> MUST REJECT (400 Bad Request)
        mockMvc.perform(patch("/api/admin/staff/" + primaryAdminId)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("role", "TAILOR"))))
                .andExpect(status().isBadRequest())
                .andExpect(status().reason(containsString("Cannot deactivate or demote the last active administrator")));
    }

    @Test
    @DisplayName("Category Deactivation Safety: Protects categories with active products (409 Conflict)")
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    void testCategoryDeactivationSafety() throws Exception {
        // 1. Create a category
        String catName = "Test Category " + System.currentTimeMillis();
        String catRes = mockMvc.perform(post("/api/admin/categories")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("name", catName, "description", "Testing safety"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        long catId = ((Number) mapper.readValue(catRes, Map.class).get("id")).longValue();

        // 2. Assign an active product to this category
        UUID prodId = UUID.randomUUID();
        jdbc.update("INSERT INTO products(id, category_id, name, default_rate, active) VALUES(?,?,?,?,?)",
                prodId, catId, "Active Product " + System.currentTimeMillis(), new BigDecimal("500.00"), true);

        // 3. Attempting to deactivate category should be rejected with 409 Conflict
        mockMvc.perform(delete("/api/admin/categories/" + catId)
                        .with(csrf()))
                .andExpect(status().isConflict())
                .andExpect(status().reason(containsString("active product(s) reference it")));

        // 4. Deactivate product first
        jdbc.update("UPDATE products SET active=false WHERE id=?", prodId);

        // 5. Now deactivating category succeeds
        mockMvc.perform(delete("/api/admin/categories/" + catId)
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message", containsString("Category deactivated")));
    }
}
