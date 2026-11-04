package com.jollytailor;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomerPrivacyAndMaskingIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper mapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("Public bill endpoint masks phone number and does not leak private customer address or notes")
    void testPublicBillMasksPhoneAndProtectsPrivateData() throws Exception {
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, name, phone, address, notes) VALUES (?, ?, ?, ?, ?)",
                customerId, "Ananya Sen", "9830087654", "12 Salt Lake Sector 1, Kolkata", "Prefers loose neck fittings");

        UUID orderId = UUID.randomUUID();
        jdbc.update("INSERT INTO tailoring_orders (id, order_number, customer_id, status, payment_status, subtotal, discount, total, amount_paid) " +
                        "VALUES (?, ?, ?, 'READY', 'PAYMENT_DUE', 1200.00, 0.00, 1200.00, 500.00)",
                orderId, "ORD-TEST-PRIV", customerId);

        try {
            // Anonymous public bill access
            mockMvc.perform(get("/api/public/bills/" + orderId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customer_name", is("Ananya Sen")))
                    .andExpect(jsonPath("$.customer_phone_masked", is("XXXXXX7654")))
                    .andExpect(jsonPath("$.customer_phone").doesNotExist())
                    .andExpect(jsonPath("$.customer_address").doesNotExist())
                    .andExpect(jsonPath("$.address").doesNotExist())
                    .andExpect(jsonPath("$.notes").doesNotExist())
                    .andExpect(jsonPath("$.is_staff", is(false)));

            // Verification by customer phone query param allows customer to view unmasked phone
            mockMvc.perform(get("/api/public/bills/" + orderId).param("phone", "9830087654"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customer_phone_unmasked", is("9830087654")));

            // Incorrect phone query param is rejected with 403 Forbidden
            mockMvc.perform(get("/api/public/bills/" + orderId).param("phone", "9999999999"))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.update("DELETE FROM tailoring_orders WHERE id = ?", orderId);
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
    }

    @Test
    @DisplayName("Unauthenticated anonymous users cannot access tailor or admin customer APIs")
    void testUnauthenticatedBlockedFromCustomerApis() throws Exception {
        mockMvc.perform(get("/api/tailor/customers"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/tailor/customers/search").param("q", "Test"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/admin/customers"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/tailor/customers")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hacker\",\"phone\":\"9830000000\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "tailor1", roles = {"TAILOR"})
    @DisplayName("Authorized tailor can search customers and retrieve customer details for order processing")
    void testAuthorizedTailorCanAccessCustomerDetails() throws Exception {
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, name, phone, address, notes) VALUES (?, ?, ?, ?, ?)",
                customerId, "Moumita Das", "9831122334", "Garia Station Road", "Needs blouse by Saturday");

        try {
            // Tailor searches customer
            mockMvc.perform(get("/api/tailor/customers").param("q", "Moumita"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                    .andExpect(jsonPath("$[0].name", is("Moumita Das")));

            // Tailor retrieves specific customer profile
            mockMvc.perform(get("/api/tailor/customers/" + customerId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id", is(customerId.toString())))
                    .andExpect(jsonPath("$.name", is("Moumita Das")))
                    .andExpect(jsonPath("$.phone", is("9831122334")))
                    .andExpect(jsonPath("$.address", is("Garia Station Road")))
                    .andExpect(jsonPath("$.notes", is("Needs blouse by Saturday")));
        } finally {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
    }

    @Test
    @WithMockUser(username = "admin", roles = {"ADMIN"})
    @DisplayName("Authorized admin can manage customer profiles and edit customer details")
    void testAuthorizedAdminCanManageCustomerProfiles() throws Exception {
        UUID customerId = UUID.randomUUID();
        jdbc.update("INSERT INTO customers (id, name, phone, address, notes) VALUES (?, ?, ?, ?, ?)",
                customerId, "Debolina Bose", "9830011223", "Behala Chowrasta", "VIP customer");

        try {
            // Admin lists customers
            mockMvc.perform(get("/api/admin/customers").param("q", "Debolina"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(greaterThanOrEqualTo(1))))
                    .andExpect(jsonPath("$[0].name", is("Debolina Bose")));

            // Admin updates customer details
            Map<String, Object> updatePayload = Map.of(
                    "name", "Debolina Bose Roy",
                    "phone", "9830011223",
                    "address", "Behala Chowrasta, Kolkata",
                    "notes", "VIP regular customer"
            );

            mockMvc.perform(put("/api/admin/customers/" + customerId)
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(updatePayload)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.name", is("Debolina Bose Roy")))
                    .andExpect(jsonPath("$.address", is("Behala Chowrasta, Kolkata")));
        } finally {
            jdbc.update("DELETE FROM customers WHERE id = ?", customerId);
        }
    }

    @Test
    @DisplayName("Verified that real shop settings and staff accounts exist, while dummy demo data is not present")
    void testShopSettingsAndRealStaffExistWithoutDemoData() {
        // Staff accounts exist and have roles
        Integer adminCount = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE role='ADMIN'", Integer.class);
        Integer tailorCount = jdbc.queryForObject("SELECT COUNT(*) FROM app_users WHERE role='TAILOR'", Integer.class);
        assertTrue(adminCount != null && adminCount >= 1, "Admin user must exist");
        assertTrue(tailorCount != null && tailorCount >= 1, "Tailor user must exist");

        // Verify dummy demo customers are not present
        Integer testCustCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customers WHERE name IN ('Test Customer', 'Test Customer 2')", Integer.class);
        assertTrue(testCustCount == null || testCustCount == 0, "Demo customers must not exist");

        // Shop settings
        String shopName = jdbc.queryForObject("SELECT setting_value FROM shop_settings WHERE setting_key='shop_name'", String.class);
        assertTrue(shopName != null && shopName.contains("Jolly Ladies Tailor"), "Shop name setting must exist");

        // Core categories
        Integer catCount = jdbc.queryForObject("SELECT COUNT(*) FROM categories WHERE active=true", Integer.class);
        assertTrue(catCount != null && catCount >= 3, "Core categories must exist");
    }
}
